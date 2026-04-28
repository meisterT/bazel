package com.google.devtools.build.lib.skyframe.serialization.analysis;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import java.util.Set;

/**
 * Public wrapper to perform client-side invalidation checks using package-private types.
 */
public final class ClientInvalidator {
  private final FileDependencyDeserializer deserializer;
  private final VersionedChangesValidator validator;
  private final FingerprintValueService fvs;

  public ClientInvalidator(FingerprintValueService fvs, Set<String> modifiedFiles, String workspaceRoot) {
    this.fvs = fvs;
    this.deserializer = new FileDependencyDeserializer(fvs.getExecutor(), fvs.getFingerprinter(), workspaceRoot);
    VersionedChanges changes = new VersionedChanges(modifiedFiles);
    this.validator = new VersionedChangesValidator(fvs.getExecutor(), changes);
  }

  /**
   * Checks if the given dependency key is invalidated by the modified files.
   */
  public ListenableFuture<Boolean> isInvalidAsync(PackedFingerprint key) {
    FileDependencyDeserializer.NestedDependenciesOrFuture nestedOrFuture = deserializer.getNestedDependencies(key, fvs.getFingerprintValueStore());
    
    return Futures.transformAsync(
        toFuture(nestedOrFuture),
        nested -> {
          NestedMatchResultTypes.NestedMatchResultOrFuture matchResultOrFuture = validator.matches(nested, 0); // validityHorizon = 0
          return Futures.transform(
              toFuture(matchResultOrFuture),
              matchResult -> !(matchResult instanceof NoMatch),
              MoreExecutors.directExecutor());
        },
        MoreExecutors.directExecutor());
  }

  private static ListenableFuture<NestedDependencies> toFuture(FileDependencyDeserializer.NestedDependenciesOrFuture orFuture) {
    if (orFuture instanceof NestedDependencies nd) {
      return Futures.immediateFuture(nd);
    } else {
      return (ListenableFuture<NestedDependencies>) orFuture;
    }
  }

  private static ListenableFuture<NestedMatchResultTypes.NestedMatchResult> toFuture(NestedMatchResultTypes.NestedMatchResultOrFuture orFuture) {
    if (orFuture instanceof NestedMatchResultTypes.NestedMatchResult nmr) {
      return Futures.immediateFuture(nmr);
    } else {
      return (ListenableFuture<NestedMatchResultTypes.NestedMatchResult>) orFuture;
    }
  }
}
