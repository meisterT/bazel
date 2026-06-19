// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import com.google.common.flogger.GoogleLogger;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.Futures.transformAsync;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.github.luben.zstd.RecyclingBufferPool;
import com.github.luben.zstd.ZstdInputStream;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.List;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.proto.DataType;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.TopLevelTargetsMatchStatus;
import com.google.protobuf.CodedInputStream;
import java.util.Arrays;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** A local implementation of {@link RemoteAnalysisCacheClient} that uses the local disk store. */
public class LocalAnalysisCacheClient implements RemoteAnalysisCacheClient {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final FingerprintValueStore store;
  private final GitWorkspaceState gitState;
  private final Executor executor;

  // Cache to avoid validating the same node multiple times in a build.
  private final ConcurrentHashMap<PackedFingerprint, ListenableFuture<Boolean>> validationCache = new ConcurrentHashMap<>();
  private final AtomicInteger cacheHits = new AtomicInteger(0);

  public LocalAnalysisCacheClient(FingerprintValueStore store, GitWorkspaceState gitState, Executor executor) {
    this.store = store;
    this.gitState = gitState;
    this.executor = executor;
  }

  public void clearValidationCache() {
    validationCache.clear();
    cacheHits.set(0);
  }

  public int getCacheHits() {
    return cacheHits.get();
  }

  @Override
  public ListenableFuture<LookupResult> lookup(byte[] key) {
    PackedFingerprint cacheKey = PackedFingerprint.fromBytes(key);
    logger.atFine().log("LocalAnalysisCacheClient.lookup called for key: %s", cacheKey);

    ListenableFuture<byte[]> bytesFuture;
    try {
      bytesFuture = store.get(cacheKey);
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Failed to get from store");
      return Futures.immediateFuture(new LookupResult(new byte[0]));
    }

    return Futures.transformAsync(
        bytesFuture,
        bytes -> {
          if (bytes == null || bytes.length == 0) {
            logger.atFine().log("LocalAnalysisCacheClient.lookup MISS in store for key: %s", cacheKey);
            return immediateFuture(new LookupResult(new byte[0])); // Miss
          }
          logger.atFine().log("LocalAnalysisCacheClient.lookup HIT in store for key: %s, size: %d", cacheKey, bytes.length);

          CodedInputStream codedIn = CodedInputStream.newInstance(bytes);
          int dataTypeVal = codedIn.readEnum();
          DataType dataType = DataType.forNumber(dataTypeVal);

          if (dataType == null) {
            return immediateFuture(new LookupResult(new byte[0])); // Invalid data
          }

          ListenableFuture<Boolean> isValidFuture;
          try {
            switch (dataType) {
              case DATA_TYPE_EMPTY:
                isValidFuture = immediateFuture(true);
                break;
              case DATA_TYPE_FILE:
                String fileKey = codedIn.readString();
                isValidFuture = immediateFuture(validateFileKey(fileKey));
                break;
              case DATA_TYPE_LISTING:
                String listingKey = codedIn.readString();
                isValidFuture = immediateFuture(validateListingKey(listingKey));
                break;
              case DATA_TYPE_ANALYSIS_NODE:
              case DATA_TYPE_EXECUTION_NODE:
                PackedFingerprint depNodeKey = PackedFingerprint.readFrom(codedIn);
                isValidFuture = validateNodeRecursively(depNodeKey);
                break;
              default:
                isValidFuture = immediateFuture(false);
            }
          } catch (IOException e) {
            logger.atWarning().withCause(e).log("Failed to parse invalidation key");
            isValidFuture = immediateFuture(false);
          }

          return transform(
              isValidFuture,
              isValid -> {
                logger.atFine().log("LocalAnalysisCacheClient.lookup key: %s, isValid: %b", cacheKey, isValid);
                if (isValid) {
                  int offset = codedIn.getTotalBytesRead();
                  byte[] valBytes = Arrays.copyOfRange(bytes, offset, bytes.length);
                  cacheHits.incrementAndGet();
                  return new LookupResult(valBytes);
                } else {
                  return new LookupResult(new byte[0]); // Miss due to invalidation
                }
              },
              directExecutor());
        },
        executor);
  }

  private boolean validateFileKey(String fileKey) {
    int delimiterIdx = fileKey.indexOf(':');
    if (delimiterIdx == -1) {
      return false;
    }
    String path = fileKey.substring(delimiterIdx + 1);
    boolean dirty = gitState.isFileDirty(path);
    if (dirty) {
      logger.atFine().log("validateFileKey: file is DIRTY: %s", path);
    }
    return !dirty;
  }

  private boolean validateListingKey(String listingKey) {
    int delimiterIdx = listingKey.indexOf(';');
    if (delimiterIdx == -1) {
      return false;
    }
    String path = listingKey.substring(delimiterIdx + 1);
    boolean dirty = gitState.isListingDirty(path);
    if (dirty) {
      logger.atFine().log("validateListingKey: listing is DIRTY: %s", path);
    }
    return !dirty;
  }

  private ListenableFuture<Boolean> validateNodeRecursively(PackedFingerprint nodeKey) {
    ListenableFuture<Boolean> future = validationCache.get(nodeKey);
    if (future != null) {
      return future;
    }

    SettableFuture<Boolean> newFuture = SettableFuture.create();
    ListenableFuture<Boolean> existingFuture = validationCache.putIfAbsent(nodeKey, newFuture);
    if (existingFuture != null) {
      return existingFuture;
    }

    newFuture.setFuture(doValidateNodeRecursively(nodeKey));
    return newFuture;
  }

  private ListenableFuture<Boolean> doValidateNodeRecursively(PackedFingerprint nodeKey) {
    logger.atFine().log("doValidateNodeRecursively called for: %s", nodeKey);
    ListenableFuture<byte[]> bytesFuture;
    try {
      bytesFuture = store.get(nodeKey);
    } catch (IOException e) {
      return immediateFuture(false);
    }

    return transformAsync(
        bytesFuture,
        bytes -> {
          if (bytes == null || bytes.length == 0) {
            return immediateFuture(false);
          }

          boolean usesZstdCompression = MagicBytes.hasMagicBytes(bytes);
          InputStream inputStream;
          if (usesZstdCompression) {
            ByteArrayInputStream byteArrayInputStream =
                new ByteArrayInputStream(bytes, 2, bytes.length - 2);
            inputStream = new ZstdInputStream(byteArrayInputStream, RecyclingBufferPool.INSTANCE);
          } else {
            inputStream = new ByteArrayInputStream(bytes);
          }

          try (inputStream) {
            CodedInputStream codedIn = CodedInputStream.newInstance(inputStream);
            int nestedCount = codedIn.readInt32();
            int fileCount = codedIn.readInt32();
            int listingCount = codedIn.readInt32();
            int sourceCount = codedIn.readInt32();

            List<ListenableFuture<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < nestedCount; i++) {
              PackedFingerprint childKey = PackedFingerprint.readFrom(codedIn);
              futures.add(validateNodeRecursively(childKey));
            }

            for (int i = 0; i < fileCount; i++) {
              String fileKey = codedIn.readString();
              futures.add(immediateFuture(validateFileKey(fileKey)));
            }

            for (int i = 0; i < listingCount; i++) {
              String listingKey = codedIn.readString();
              futures.add(immediateFuture(validateListingKey(listingKey)));
            }

            for (int i = 0; i < sourceCount; i++) {
              String sourceKey = codedIn.readString();
              futures.add(immediateFuture(validateFileKey(sourceKey)));
            }

            return transform(
                Futures.allAsList(futures),
                results -> {
                  for (boolean r : results) {
                    if (!r) {
                      return false;
                    }
                  }
                  return true;
                },
                directExecutor());

          } catch (Exception e) {
            logger.atWarning().withCause(e).log("Failed to validate node recursively");
            return immediateFuture(false);
          }
        },
        executor);
  }

  @Override
  public Stats getStats() {
    return EMPTY_STATS;
  }

  @Override
  public LookupTopLevelTargetsResult lookupTopLevelTargets(
      long evaluatingVersion,
      String configurationHash,
      boolean useFakeStampData,
      String bazelVersion) {
    return new LookupTopLevelTargetsResult(
        TopLevelTargetsMatchStatus.MATCH_STATUS_MATCH.getNumber(), "Local cache matches");
  }

  @Override
  public void bailOutDueToMissingFingerprint() {}
}
