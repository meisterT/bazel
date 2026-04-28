package com.google.devtools.build.lib.skyframe.serialization;

import com.google.common.collect.ImmutableSet;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

/** Helper to run Git commands and extract modified files. */
public final class GitDiffHelper {

  /**
   * Runs `git diff --name-only <baseCommit>` and combines with untracked files.
   * Runs inside the specified workspaceRoot if it is a Git repository.
   *
   * @param workspaceRoot the Bazel workspace root path.
   * @param baseCommit the base commit hash to diff against.
   * @return a set of modified file paths relative to workspace root.
   */
  public static ImmutableSet<String> getModifiedFiles(String workspaceRoot, String baseCommit) throws IOException {
    if (workspaceRoot == null || !new File(workspaceRoot, ".git").exists()) {
      return ImmutableSet.of();
    }

    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    // Run git diff
    ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", baseCommit);
    pb.directory(new File(workspaceRoot));
    Process p = pb.start();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        builder.add(line);
      }
    }

    // Run git ls-files --others --exclude-standard to get untracked files
    pb = new ProcessBuilder("git", "ls-files", "--others", "--exclude-standard");
    pb.directory(new File(workspaceRoot));
    p = pb.start();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("bazel-")) {
          builder.add(trimmed);
        }
      }
    }

    return builder.build();
  }

  public static String detectBaseCommit(String workspaceRoot) throws IOException {
    if (workspaceRoot == null || !new File(workspaceRoot, ".git").exists()) {
      return "";
    }

    ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
    pb.directory(new File(workspaceRoot));
    Process p = pb.start();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      String line = reader.readLine();
      return line != null ? line.trim() : "";
    }
  }

  private GitDiffHelper() {}
}
