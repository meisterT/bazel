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
//
// Clean up imports.
package com.google.devtools.build.lib.versioning;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.vfs.DigestUtils;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.SyscallCache;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** A {@link LongVersionGetter} that uses content hashes (digests) for versioning. */
public final class ContentHashVersionGetter implements LongVersionGetter {

  @Override
  public long getFilePathOrSymlinkVersion(Path path) throws IOException {
    if (path.isSymbolicLink()) {
      // For symlinks, we hash the target path.
      String target = path.readSymbolicLink().getPathString();
      return hashStringToLong(target);
    }
    // For regular files, we use the digest.
    byte[] digest = DigestUtils.getDigestWithManualFallback(path, SyscallCache.NO_CACHE);
    return convertDigestToLong(digest);
  }

  @Override
  public long getDirectoryListingVersion(Path path) throws IOException {
    Collection<Dirent> dirents = path.readdir(Symlinks.NOFOLLOW);
    List<Dirent> sorted = new ArrayList<>(dirents);
    sorted.sort(Comparator.comparing(Dirent::getName));
    Hasher hasher = Hashing.murmur3_128().newHasher();
    for (Dirent dirent : sorted) {
      hasher.putString(dirent.getName(), StandardCharsets.UTF_8);
      hasher.putInt(dirent.getType().ordinal());
    }
    return convertDigestToLong(hasher.hash().asBytes());
  }

  @Override
  public long getNonexistentPathVersion(Path path) throws IOException {
    return MINIMAL;
  }

  private static long hashStringToLong(String s) {
    byte[] digest = Hashing.murmur3_128().hashString(s, StandardCharsets.UTF_8).asBytes();
    return convertDigestToLong(digest);
  }

  private static long convertDigestToLong(byte[] digest) {
    if (digest == null || digest.length == 0) {
      return MINIMAL;
    }
    long val;
    if (digest.length >= 8) {
      val = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN).getLong();
    } else {
      // Pad with zeros if digest is too short (unlikely for real digests, but possible in tests)
      byte[] padded = new byte[8];
      System.arraycopy(digest, 0, padded, 0, digest.length);
      val = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
    val = val & Long.MAX_VALUE;
    if (val == CURRENT_VERSION) {
      val--;
    }
    return val;
  }
}
