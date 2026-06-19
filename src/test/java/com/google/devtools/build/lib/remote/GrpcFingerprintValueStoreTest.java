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
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.Platform;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.remote.common.ActionKey;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatus;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.protobuf.ByteString;
import java.io.OutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public class GrpcFingerprintValueStoreTest {

  private static final DigestUtil DIGEST_UTIL =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);

  private RemoteCacheClient mockCacheClient;
  private GrpcFingerprintValueStore store;

  @Before
  public void setUp() {
    mockCacheClient = mock(RemoteCacheClient.class);
    store = new GrpcFingerprintValueStore(mockCacheClient, DIGEST_UTIL);
  }

  private static KeyBytesProvider fingerprintOf(byte[] bytes) {
    return new KeyBytesProvider() {
      @Override
      public byte[] toBytes() {
        return bytes;
      }

      @Override
      public byte[] concat(byte[] suffix) {
        byte[] result = new byte[bytes.length + suffix.length];
        System.arraycopy(bytes, 0, result, 0, bytes.length);
        System.arraycopy(suffix, 0, result, bytes.length, suffix.length);
        return result;
      }
    };
  }

  private Action getAction(KeyBytesProvider fingerprint) {
    String hexHash = HashCode.fromBytes(fingerprint.toBytes()).toString();
    Digest inputRootDigest = DigestUtil.buildDigest(hexHash, 0);
    Digest commandDigest = DIGEST_UTIL.compute(GrpcFingerprintValueStore.DUMMY_COMMAND);
    return Action.newBuilder()
        .setInputRootDigest(inputRootDigest)
        .setCommandDigest(commandDigest)
        .setPlatform(GrpcFingerprintValueStore.SKYCACHE_PLATFORM)
        .build();
  }

  @Test
  public void put_uploadsValueAndActionAndResult() throws Exception {
    // Arrange
    KeyBytesProvider fingerprint = fingerprintOf(new byte[] {1, 2, 3});
    byte[] serializedBytes = new byte[] {4, 5, 6};
    Digest valueDigest = DIGEST_UTIL.compute(serializedBytes);

    Action action = getAction(fingerprint);
    Digest actionDigest = DIGEST_UTIL.compute(action);
    ActionKey actionKey = DIGEST_UTIL.asActionKey(actionDigest);
    Digest commandDigest = DIGEST_UTIL.compute(GrpcFingerprintValueStore.DUMMY_COMMAND);

    when(mockCacheClient.uploadBlob(any(), eq(valueDigest), any(ByteString.class), eq(false)))
        .thenReturn(Futures.immediateFuture(null));
    when(mockCacheClient.uploadBlob(any(), eq(actionDigest), any(ByteString.class), eq(false)))
        .thenReturn(Futures.immediateFuture(null));
    when(mockCacheClient.uploadBlob(any(), eq(commandDigest), any(ByteString.class), eq(false)))
        .thenReturn(Futures.immediateFuture(null));
    when(mockCacheClient.uploadActionResult(any(), eq(actionKey), any()))
        .thenReturn(Futures.immediateFuture(null));

    // Act
    WriteStatus writeStatus = store.put(fingerprint, serializedBytes);

    // Assert
    assertThat(writeStatus.get()).isTrue();
    verify(mockCacheClient)
        .uploadBlob(any(), eq(valueDigest), eq(ByteString.copyFrom(serializedBytes)), eq(false));
    verify(mockCacheClient)
        .uploadBlob(any(), eq(actionDigest), eq(action.toByteString()), eq(false));
    verify(mockCacheClient)
        .uploadBlob(any(), eq(commandDigest), eq(GrpcFingerprintValueStore.DUMMY_COMMAND.toByteString()), eq(false));

    ArgumentCaptor<ActionResult> actionResultCaptor = ArgumentCaptor.forClass(ActionResult.class);
    verify(mockCacheClient).uploadActionResult(any(), eq(actionKey), actionResultCaptor.capture());
    ActionResult actionResult = actionResultCaptor.getValue();
    assertThat(actionResult.getOutputFilesCount()).isEqualTo(1);
    OutputFile file = actionResult.getOutputFiles(0);
    assertThat(file.getPath()).isEqualTo("entry");
    assertThat(file.getDigest()).isEqualTo(valueDigest);
  }

  @Test
  public void get_cacheHit_downloadsBlob() throws Exception {
    // Arrange
    KeyBytesProvider fingerprint = fingerprintOf(new byte[] {1, 2, 3});
    byte[] serializedBytes = new byte[] {4, 5, 6};
    Digest valueDigest = DIGEST_UTIL.compute(serializedBytes);
    ActionKey actionKey =
        GrpcFingerprintValueStore.fingerprintToActionKey(fingerprint, DIGEST_UTIL, GrpcFingerprintValueStore.SKYCACHE_PLATFORM);

    ActionResult actionResult =
        ActionResult.newBuilder()
            .addOutputFiles(
                OutputFile.newBuilder()
                    .setPath("entry")
                    .setDigest(valueDigest)
                    .setIsExecutable(false)
                    .build())
            .build();

    when(mockCacheClient.downloadActionResult(any(), eq(actionKey), eq(false), any()))
        .thenReturn(Futures.immediateFuture(actionResult));

    // downloadBlob writes to OutputStream. We need to mock this behavior.
    when(mockCacheClient.downloadBlob(any(), eq(valueDigest), any(OutputStream.class)))
        .thenAnswer(
            invocation -> {
              OutputStream out = invocation.getArgument(2);
              out.write(serializedBytes);
              return Futures.immediateFuture(null);
            });

    // Act
    ListenableFuture<byte[]> future = store.get(fingerprint);

    // Assert
    byte[] result = future.get();
    assertThat(result).isEqualTo(serializedBytes);
    verify(mockCacheClient).downloadActionResult(any(), eq(actionKey), eq(false), any());
    verify(mockCacheClient).downloadBlob(any(), eq(valueDigest), any(OutputStream.class));
  }

  @Test
  public void get_cacheMiss_returnsNull() throws Exception {
    // Arrange
    KeyBytesProvider fingerprint = fingerprintOf(new byte[] {1, 2, 3});
    ActionKey actionKey =
        GrpcFingerprintValueStore.fingerprintToActionKey(fingerprint, DIGEST_UTIL, GrpcFingerprintValueStore.SKYCACHE_PLATFORM);

    when(mockCacheClient.downloadActionResult(any(), eq(actionKey), eq(false), any()))
        .thenReturn(Futures.immediateFuture(null));

    // Act
    ListenableFuture<byte[]> future = store.get(fingerprint);

    // Assert
    byte[] result = future.get();
    assertThat(result).isNull();
  }

  @Test
  public void get_missingBlob_returnsNull() throws Exception {
    // Arrange
    KeyBytesProvider fingerprint = fingerprintOf(new byte[] {1, 2, 3});
    byte[] serializedBytes = new byte[] {4, 5, 6};
    Digest valueDigest = DIGEST_UTIL.compute(serializedBytes);
    ActionKey actionKey =
        GrpcFingerprintValueStore.fingerprintToActionKey(fingerprint, DIGEST_UTIL, GrpcFingerprintValueStore.SKYCACHE_PLATFORM);

    ActionResult actionResult =
        ActionResult.newBuilder()
            .addOutputFiles(
                OutputFile.newBuilder()
                    .setPath("entry")
                    .setDigest(valueDigest)
                    .setIsExecutable(false)
                    .build())
            .build();

    when(mockCacheClient.downloadActionResult(any(), eq(actionKey), eq(false), any()))
        .thenReturn(Futures.immediateFuture(actionResult));
    when(mockCacheClient.downloadBlob(any(), eq(valueDigest), any(OutputStream.class)))
        .thenReturn(Futures.immediateFailedFuture(new CacheNotFoundException(valueDigest)));

    // Act
    ListenableFuture<byte[]> future = store.get(fingerprint);

    // Assert
    byte[] result = future.get();
    assertThat(result).isNull();
  }
}
