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
package com.google.devtools.build.lib.versioning;

import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;

/** A {@link LongVersionGetter} that uses the last modification time of the file. */
public final class MtimeVersionGetter implements LongVersionGetter {

  @Override
  public long getFilePathOrSymlinkVersion(Path path) throws IOException {
    return path.getLastModifiedTime(Symlinks.NOFOLLOW);
  }

  @Override
  public long getDirectoryListingVersion(Path path) throws IOException {
    return path.getLastModifiedTime();
  }

  @Override
  public long getNonexistentPathVersion(Path path) throws IOException {
    return MINIMAL;
  }
}
