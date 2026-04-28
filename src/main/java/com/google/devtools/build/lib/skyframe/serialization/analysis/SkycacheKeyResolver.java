package com.google.devtools.build.lib.skyframe.serialization.analysis;

import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.vfs.Path;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

/** Utility class to automatically resolve SCM commit hash key for Skycache. */
public final class SkycacheKeyResolver {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private SkycacheKeyResolver() {}

  /**
   * Attempts to automatically resolve the workspace commit hash.
   * Returns empty string if it fails, falling back to default "manifest" prefix.
   */
  public static String resolve(Path workspaceRoot) {
    if (workspaceRoot == null) {
      return "";
    }

    // 1. Attempt Git resolution
    Path gitDir = workspaceRoot.getChild(".git");
    if (gitDir.exists()) {
      try {
        return runCommand(workspaceRoot.getPathString(), "git", "rev-parse", "HEAD");
      } catch (IOException | InterruptedException e) {
        logger.atWarning().withCause(e).log("Failed to run git rev-parse HEAD to determine Skycache key");
      }
    }

    return "";
  }

  private static String runCommand(String dir, String... command) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(new java.io.File(dir));
    pb.redirectErrorStream(true);
    Process process = pb.start();

    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line.trim());
      }
    }

    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IOException("Command failed with exit code " + exitCode + ": " + output);
    }

    return output.toString();
  }
}
