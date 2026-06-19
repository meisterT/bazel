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
package com.google.devtools.build.lib.skyframe.serialization;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** A {@link FingerprintValueStore} that stores values on local disk. */
public final class DiskFingerprintValueStore implements FingerprintValueStore {
  private final Path cacheDir;
  private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
  private static final HexFormat HEX_FORMAT = HexFormat.of().withLowerCase();

  public DiskFingerprintValueStore(Path cacheDir) {
    this.cacheDir = cacheDir;
    try {
      cacheDir.createDirectoryAndParents();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to create cache directory: " + cacheDir, e);
    }
  }

  private String getFileName(KeyBytesProvider fingerprint) {
    return HEX_FORMAT.formatHex(
        com.google.common.hash.Hashing.murmur3_128().hashBytes(fingerprint.toBytes()).asBytes());
  }

  private Path getFilePath(KeyBytesProvider fingerprint) {
    return cacheDir.getChild(getFileName(fingerprint));
  }

  @Override
  public void shutdown() {
    executor.shutdown();
  }

  @Override
  public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
    SettableWriteStatus future = SettableWriteStatus.create();
    executor.execute(() -> {
      try {
        Path path = getFilePath(fingerprint);
        com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(path, serializedBytes);
        future.set(true);
      } catch (IOException e) {
        future.setException(e);
      }
    });
    return future;
  }

  @Override
  public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) throws IOException {
    SettableFuture<byte[]> future = SettableFuture.create();
    executor.execute(() -> {
      Path path = getFilePath(fingerprint);
      if (!path.exists()) {
        future.setException(new MissingFingerprintValueException(fingerprint));
      } else {
        try {
          future.set(com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(path));
        } catch (IOException e) {
          future.setException(e);
        }
      }
    });
    return future;
  }

  private static class SettableWriteStatus extends com.google.common.util.concurrent.AbstractFuture<Boolean> implements WriteStatus {
    public static SettableWriteStatus create() {
      return new SettableWriteStatus();
    }
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    public boolean set(Boolean value) {
      return super.set(value);
    }
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    public boolean setException(Throwable throwable) {
      return super.setException(throwable);
    }
  }
}
