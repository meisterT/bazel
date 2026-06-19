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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.SkycacheMetadataParams;
import com.google.devtools.build.lib.util.SerializedAbruptExitException;
import javax.annotation.Nullable;

/**
 * A {@link RemoteAnalysisCachingServicesSupplier} that delegates to either a local or a remote
 * supplier based on the configured storage type.
 */
public final class DelegatingRemoteAnalysisCachingServicesSupplier
    implements RemoteAnalysisCachingServicesSupplier, CommandLifecycleSupplier {

  private final RemoteAnalysisCachingServicesSupplier localSupplier;
  @Nullable private final RemoteAnalysisCachingServicesSupplier remoteSupplier;
  private RemoteAnalysisCachingServicesSupplier activeSupplier;

  public DelegatingRemoteAnalysisCachingServicesSupplier(
      RemoteAnalysisCachingServicesSupplier localSupplier,
      @Nullable RemoteAnalysisCachingServicesSupplier remoteSupplier) {
    this.localSupplier = localSupplier;
    this.remoteSupplier = remoteSupplier;
    this.activeSupplier = localSupplier; // Default to local
  }

  @Override
  public void beforeCommand(Object env) {
    if (localSupplier instanceof CommandLifecycleSupplier) {
      ((CommandLifecycleSupplier) localSupplier).beforeCommand(env);
    }
    if (remoteSupplier instanceof CommandLifecycleSupplier) {
      ((CommandLifecycleSupplier) remoteSupplier).beforeCommand(env);
    }
  }

  @Override
  public void configure(
      RemoteAnalysisCachingConfig config, @Nullable ClientId clientId, String buildId)
      throws SerializedAbruptExitException {
    if (config.storageType() == RemoteAnalysisCacheStorageType.REMOTE && remoteSupplier != null) {
      activeSupplier = remoteSupplier;
    } else {
      activeSupplier = localSupplier;
    }
    activeSupplier.configure(config, clientId, buildId);
  }

  @Override
  @Nullable
  public ListenableFuture<? extends FingerprintValueStore> getFingerprintValueStore() {
    return activeSupplier.getFingerprintValueStore();
  }

  @Override
  @Nullable
  public ListenableFuture<? extends RemoteAnalysisCacheClient> getAnalysisCacheClient() {
    return activeSupplier.getAnalysisCacheClient();
  }

  @Override
  @Nullable
  public ListenableFuture<? extends RemoteAnalysisMetadataWriter> getMetadataWriter() {
    return activeSupplier.getMetadataWriter();
  }

  @Override
  @Nullable
  public SkycacheMetadataParams getSkycacheMetadataParams() {
    return activeSupplier.getSkycacheMetadataParams();
  }

  @Override
  public void resetCommandState() {
    localSupplier.resetCommandState();
    if (remoteSupplier != null) {
      remoteSupplier.resetCommandState();
    }
  }

  @Override
  public void blazeShutdown() {
    localSupplier.blazeShutdown();
    if (remoteSupplier != null) {
      remoteSupplier.blazeShutdown();
    }
  }
}
