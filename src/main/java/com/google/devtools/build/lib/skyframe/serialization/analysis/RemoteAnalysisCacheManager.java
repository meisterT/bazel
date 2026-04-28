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

import com.google.devtools.build.lib.skyframe.serialization.GitDiffHelper;
import com.google.devtools.build.lib.skyframe.serialization.InvalidationValidator;
import com.google.protobuf.CodedInputStream;
import com.google.devtools.build.lib.skyframe.serialization.proto.DataType;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.skyframe.serialization.SkycacheMetadataParams;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions.RemoteAnalysisCacheMode;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** A collection of dependencies and minor bits of functionality for remote analysis caching. */
// Non-final for mockability
public class RemoteAnalysisCacheManager implements RemoteAnalysisCachingDependenciesProvider {
  private final RemoteAnalysisCacheMode mode;

  private final Future<? extends RemoteAnalysisCacheClient> analysisCacheClient;
  private final Future<? extends AnalysisCacheInvalidator> analysisCacheInvalidator;
  
  private com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService fingerprintService;
  private com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs codecs;

  private final Collection<Label> topLevelTargets;
  private final Optional<Predicate<PackageIdentifier>> activeDirectoriesMatcher;
  private final com.google.devtools.build.lib.vfs.Path workspaceRoot;

  private final ExtendedEventHandler eventHandler;

  private final boolean areMetadataQueriesEnabled;
  private final SkycacheMetadataParams skycacheMetadataParams;

  private boolean bailedOut;

  private final boolean minimizeMemory;

  /**
   * A collection of various parts of this class that various parts of Bazel (cache reading, cache
   * writing, in-memory bookkeeping) need.
   */
  public record AnalysisDeps(
      RemoteAnalysisCachingDependenciesProvider deps,
      RemoteAnalysisCacheReaderDepsProvider readerDeps,
      SerializationDependenciesProvider serializationDeps) {}

  public static RemoteAnalysisCacheManager createDisabled() {
    return new RemoteAnalysisCacheManager();
  }

  private RemoteAnalysisCacheManager() {
    this.mode = RemoteAnalysisCacheMode.OFF;
    this.analysisCacheClient = null;
    this.analysisCacheInvalidator = null;
    this.topLevelTargets = ImmutableList.of();
    this.activeDirectoriesMatcher = Optional.empty();
    this.minimizeMemory = false;
    this.eventHandler = null;
    this.skycacheMetadataParams = null;
    this.areMetadataQueriesEnabled = false;
    this.workspaceRoot = null;
  }


  RemoteAnalysisCacheManager(
      RemoteAnalysisCacheMode mode,
      boolean areMetadataQueriesEnabled,
      ExtendedEventHandler eventHandler,
      SkycacheMetadataParams skycacheMetadataParams,
      Future<? extends RemoteAnalysisCacheClient> analysisCacheClient,
      Future<? extends AnalysisCacheInvalidator> analysisCacheInvalidator,
      com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService fingerprintService,
      com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs codecs,
      Collection<Label> topLevelTargets,
      Optional<Predicate<PackageIdentifier>> activeDirectoriesMatcher,
      boolean minimizeMemory,
      com.google.devtools.build.lib.vfs.Path workspaceRoot) {
    this.mode = mode;
    this.analysisCacheClient = analysisCacheClient;
    this.analysisCacheInvalidator = analysisCacheInvalidator;
    this.fingerprintService = fingerprintService;
    this.codecs = codecs;
    this.topLevelTargets = topLevelTargets;
    this.activeDirectoriesMatcher = activeDirectoriesMatcher;
    this.minimizeMemory = minimizeMemory;
    this.eventHandler = eventHandler;
    this.skycacheMetadataParams = skycacheMetadataParams;
    this.areMetadataQueriesEnabled = areMetadataQueriesEnabled;
    this.workspaceRoot = workspaceRoot;
  }

  @Override
  public RemoteAnalysisCacheMode mode() {
    return mode;
  }

  @Override
  public void queryMetadataAndMaybeBailout() throws InterruptedException {
    Preconditions.checkState(mode == RemoteAnalysisCacheMode.DOWNLOAD);
    if (!areMetadataQueriesEnabled) {
      return;
    }
    if (skycacheMetadataParams.getTargets().isEmpty()) {
      eventHandler.handle(
          Event.warn("Skycache: Not querying Skycache metadata because invocation has no targets"));
    } else {
      try {
        var client = RemoteAnalysisCacheDeps.resolveWithTimeout(analysisCacheClient, "analysis cache client");
        if (client == null) {
          eventHandler.handle(Event.warn("Skycache: Metadata store unavailable (client is null). Skipping metadata query."));
          return;
        }
        LookupTopLevelTargetsResult result =
            client.lookupTopLevelTargets(
                skycacheMetadataParams.getEvaluatingVersion(),
                skycacheMetadataParams.getConfigurationHash(),
                skycacheMetadataParams.getUseFakeStampData(),
                skycacheMetadataParams.getBazelVersion());

        Event event =
            switch (result.status()) {
              case MATCH_STATUS_MATCH -> Event.info("Skycache: " + result.statusMessage());
              default -> {
                bailedOut = true;
                yield Event.warn("Skycache: " + result.statusMessage());
              }
            };
        eventHandler.handle(event);
      } catch (ExecutionException | TimeoutException e) {
        eventHandler.handle(Event.warn("Skycache: Error with metadata store: " + e.getMessage()));
      }
    }
  }

  private void checkEnabled() {
    Preconditions.checkState(
        mode != RemoteAnalysisCacheMode.OFF, "Remote analysis cache is disabled");
  }

  @Override
  public Set<SkyKey> lookupKeysToInvalidate(
      Supplier<ImmutableSet<SkyKey>> keysToLookupSupplier,
      RemoteAnalysisCachingServerState remoteAnalysisCachingState)
      throws InterruptedException {
    checkEnabled();
      // Milestone 2: Hook up validation in cache lookup
      // Read manifest to get fingerprints
       var fvs = fingerprintService;
      if (fvs != null) {
        String baseCommit = null;
        try {
          baseCommit = GitDiffHelper.detectBaseCommit(workspaceRoot != null ? workspaceRoot.getPathString() : null);
        } catch (java.io.IOException e) {
          eventHandler.handle(Event.warn("Skycache: Failed to detect base commit: " + e.getMessage()));
        }
        if (baseCommit != null) {
          if (skycacheMetadataParams == null) {
            eventHandler.handle(Event.warn("Skycache: skycacheMetadataParams is null, falling back to coarse invalidation"));
            return keysToLookupSupplier.get();
          }
          String manifestKeyPrefix = baseCommit.isEmpty() ? "manifest" : baseCommit;
          String manifestKey = manifestKeyPrefix + ":" + skycacheMetadataParams.getConfigurationHash();
          try {
            byte[] manifestBytes = fvs.get(new com.google.devtools.build.lib.skyframe.serialization.StringKey(manifestKey)).get();
            if (manifestBytes != null) {
              Set<String> modifiedFiles;
              String testModifiedFiles = System.getProperty("bazel.skycache.test.modified_files");
              if (testModifiedFiles != null) {
                modifiedFiles = testModifiedFiles.isEmpty()
                    ? ImmutableSet.of()
                    : ImmutableSet.copyOf(testModifiedFiles.split(","));
              } else {
                modifiedFiles = GitDiffHelper.getModifiedFiles(workspaceRoot != null ? workspaceRoot.getPathString() : null, baseCommit);
              }
              eventHandler.handle(Event.info("Skycache: Aligned manifest loaded. Size: " + manifestBytes.length + " bytes. Git modified files count: " + modifiedFiles.size()));
              ImmutableSet.Builder<SkyKey> invalidKeysBuilder = ImmutableSet.builder();
              
              // Manifest format: [fpBytes] [keyBytesLength] [keyBytes]
              int fpSize = 16; // Assuming 128-bit hash produced short safe filenames earlier
              int i = 0;
              int entriesAnalyzed = 0;
              while (i < manifestBytes.length) {
                byte[] fpBytes = new byte[fpSize];
                System.arraycopy(manifestBytes, i, fpBytes, 0, fpSize);
                i += fpSize;
                
                int keyLen = java.nio.ByteBuffer.wrap(manifestBytes, i, 4).getInt();
                i += 4;
                byte[] keyBytes = new byte[keyLen];
                System.arraycopy(manifestBytes, i, keyBytes, 0, keyLen);
                i += keyLen;
                
                com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint fp = com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint.fromBytes(fpBytes);
                
                // Deserialize key to get SkyKey
                com.google.devtools.build.skyframe.SkyKey nodeKey = null;
                try {
                  nodeKey = (com.google.devtools.build.skyframe.SkyKey) codecs.deserializeMemoizedAndBlocking(fingerprintService, com.google.protobuf.ByteString.copyFrom(keyBytes));
                } catch (Exception e) {
                  eventHandler.handle(Event.warn("Skycache: Failed to deserialize key from manifest: " + e.getMessage()));
                  continue; // Skip this entry if key deserialization fails
                }
                
                entriesAnalyzed++;
                // Fetch entry to get invalidation data
                byte[] entryBytes = fingerprintService.get(fp).get();
                if (entryBytes != null) {
                  CodedInputStream codedIn = CodedInputStream.newInstance(entryBytes);
                  int typeNum = codedIn.readEnum();
                  DataType type = DataType.forNumber(typeNum);
                  
                  if (type == null) {
                    invalidKeysBuilder.add(nodeKey); // Corrupted entry, invalidate to be safe
                    continue;
                  }

                  switch (type) {
                    case DATA_TYPE_EMPTY:
                      // No dependencies, valid.
                      break;
                    case DATA_TYPE_FILE: {
                      String cacheKey = codedIn.readString();
                      int delimIdx = cacheKey.indexOf(':');
                      if (delimIdx != -1) {
                        String path = cacheKey.substring(delimIdx + 1);
                        if (modifiedFiles.contains(path)) {
                          invalidKeysBuilder.add(nodeKey);
                        }
                      }
                      break;
                    }
                    case DATA_TYPE_LISTING: {
                      String cacheKey = codedIn.readString();
                      int delimIdx = cacheKey.indexOf(';');
                      if (delimIdx != -1) {
                        String path = cacheKey.substring(delimIdx + 1);
                        // Check if the modifiedFiles contains any file under this directory path
                        if (modifiedFiles.stream().anyMatch(f -> f.startsWith(path))) {
                          invalidKeysBuilder.add(nodeKey);
                        }
                      }
                      break;
                    }
                    case DATA_TYPE_ANALYSIS_NODE:
                    case DATA_TYPE_EXECUTION_NODE: {
                      // It's a nested invalidation node (transitive dependencies)
                      com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint nestedKey =
                          com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint.readFrom(codedIn);

                      // Use ClientInvalidator to recursively validate
                       ClientInvalidator invalidator = new ClientInvalidator(fingerprintService, modifiedFiles, workspaceRoot != null ? workspaceRoot.getPathString() : null);
                       if (invalidator.isInvalidAsync(nestedKey).get()) {
                        invalidKeysBuilder.add(nodeKey);
                      }
                      break;
                    }
                  }
                }
              }
              var invalidKeys = invalidKeysBuilder.build();
              eventHandler.handle(Event.info("Skycache: Invalidation check complete. Analyzed entries: " + entriesAnalyzed + ". Invalidated keys: " + invalidKeys.size()));
              return invalidKeys;
            }
          } catch (Exception e) {
            eventHandler.handle(Event.warn("Skycache: Error reading manifest or performing validation: " + e.getMessage()));
          }
        }
      }
      
      // Fall back to play safe if manifest read or validation fails
      return keysToLookupSupplier.get();
  }

  @Override
  public boolean bailedOut() {
    checkEnabled();
    return bailedOut;
  }

  @Override
  public void computeSelectionAndMinimizeMemory(InMemoryGraph graph) {
    checkEnabled();
    FrontierSerializer.computeSelectionAndMinimizeMemory(
        graph, topLevelTargets, activeDirectoriesMatcher);
  }

  @Override
  public boolean shouldMinimizeMemory() {
    checkEnabled();
    return minimizeMemory;
  }

}
