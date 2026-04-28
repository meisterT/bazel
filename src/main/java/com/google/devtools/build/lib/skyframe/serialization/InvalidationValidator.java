package com.google.devtools.build.lib.skyframe.serialization;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Set;

/** Validator for fine-grained client-side invalidation. */
public final class InvalidationValidator {

  /**
   * Checks if any modified file path intersects with the list of dependency file paths.
   *
   * @param modifiedFiles set of modified file paths from Git.
   * @param dependencyFiles list of dependency file paths from cache metadata.
   * @return true if they intersect (invalid), false otherwise.
   */
  public static boolean shouldInvalidate(Set<String> modifiedFiles, Set<String> dependencyFiles) {
    return !Sets.intersection(modifiedFiles, dependencyFiles).isEmpty();
  }

  private InvalidationValidator() {}
}
