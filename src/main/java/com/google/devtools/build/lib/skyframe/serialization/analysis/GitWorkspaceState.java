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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.vfs.Path;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

/** Tracks dirty files and listings in the workspace using Git. */
public class GitWorkspaceState {
  @VisibleForTesting public static GitWorkspaceState mockInstance = null;

  private final Path workspaceRoot;
  private ImmutableSet<String> dirtyFiles = ImmutableSet.of();
  private ImmutableSet<String> dirtyListings = ImmutableSet.of();
  private boolean expectingRenameSource = false;

  public GitWorkspaceState(Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  public void update() {
    if (mockInstance != null) {
      mockInstance.update();
      return;
    }
    Set<String> files = new HashSet<>();
    Set<String> listings = new HashSet<>();
    expectingRenameSource = false;
    try {
      Process process = new ProcessBuilder("git", "status", "--porcelain", "-z")
          .directory(new java.io.File(workspaceRoot.getPathString()))
          .start();
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1) {
          if (ch == 0) {
            parseLine(sb.toString(), files, listings);
            sb.setLength(0);
          } else {
            sb.append((char) ch);
          }
        }
        if (sb.length() > 0) {
          parseLine(sb.toString(), files, listings);
        }
      }
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        StringBuilder errorSb = new StringBuilder();
        try (BufferedReader errorReader = new BufferedReader(
            new InputStreamReader(process.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
          String line;
          while ((line = errorReader.readLine()) != null) {
            errorSb.append(line).append("\n");
          }
        }
        System.err.println("WARNING Skycache: git status exited with code " + exitCode + ": " + errorSb.toString().trim());
      }
    } catch (Exception e) {
      System.err.println("WARNING Skycache: Failed to run git status: " + e.getMessage());
    }
    this.dirtyFiles = ImmutableSet.copyOf(files);
    this.dirtyListings = ImmutableSet.copyOf(listings);
    System.out.println("DEBUG Skycache: Updated dirty files: " + this.dirtyFiles.size() + ", dirty listings: " + this.dirtyListings.size());
  }

  private void parseLine(String line, Set<String> files, Set<String> listings) {
    if (expectingRenameSource) {
      files.add(line);
      addParentToListings(line, listings);
      expectingRenameSource = false;
      return;
    }
    if (line.length() < 4) {
      return;
    }
    String status = line.substring(0, 2);
    String path = line.substring(3);
    files.add(path);

    char x = status.charAt(0);
    char y = status.charAt(1);
    boolean isRename = x == 'R' || y == 'R';
    boolean affectsListing = x == 'A' || x == 'D' || isRename || y == 'A' || y == 'D' || status.equals("??");

    if (affectsListing) {
      addParentToListings(path, listings);
    }
    if (isRename) {
      expectingRenameSource = true;
    }
  }

  private void addParentToListings(String path, Set<String> listings) {
    int lastSlash = path.lastIndexOf('/');
    if (lastSlash != -1) {
      listings.add(path.substring(0, lastSlash));
    } else {
      listings.add("");
    }
  }

  public boolean isFileDirty(String path) {
    if (mockInstance != null) {
      return mockInstance.isFileDirty(path);
    }
    return dirtyFiles.contains(path);
  }

  public boolean isListingDirty(String path) {
    if (mockInstance != null) {
      return mockInstance.isListingDirty(path);
    }
    return dirtyListings.contains(path);
  }
}
