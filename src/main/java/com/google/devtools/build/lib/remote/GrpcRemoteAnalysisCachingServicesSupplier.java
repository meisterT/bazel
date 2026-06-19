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

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId;
import com.google.devtools.build.lib.skyframe.serialization.analysis.CommandLifecycleSupplier;
import com.google.devtools.build.lib.skyframe.serialization.analysis.LocalAnalysisCacheClient;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheClient;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingConfig;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesSupplier;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import javax.annotation.Nullable;

/**
 * A {@link RemoteAnalysisCachingServicesSupplier} that uses {@link GrpcFingerprintValueStore} to
 * talk to a remote gRPC cache.
 */
public final class GrpcRemoteAnalysisCachingServicesSupplier
    implements RemoteAnalysisCachingServicesSupplier, CommandLifecycleSupplier {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  @Nullable private CommandEnvironment env;

  @Nullable private GrpcFingerprintValueStore store;
  @Nullable private ListenableFuture<GrpcFingerprintValueStore> storeFuture;
  @Nullable private LocalAnalysisCacheClient client;
  @Nullable private ListenableFuture<LocalAnalysisCacheClient> clientFuture;

  public GrpcRemoteAnalysisCachingServicesSupplier() {}

  @Override
  public void beforeCommand(Object env) {
    logger.atFine().log("beforeCommand called");
    this.env = (CommandEnvironment) env;
    if (client != null) {
      client.clearValidationCache();
    }
    this.store = null;
    this.storeFuture = null;
    this.client = null;
    this.clientFuture = null;
  }

  @Override
  public void configure(
      RemoteAnalysisCachingConfig config, @Nullable ClientId clientId, String buildId)
      throws com.google.devtools.build.lib.util.SerializedAbruptExitException {
    logger.atFine().log("configure called");
    if (storeFuture != null) {
      return; // Already configured
    }

    if (env == null) {
      logger.atWarning().log("configure: env is null, cannot configure remote supplier");
      return;
    }

    RemoteModule remoteModule = env.getRuntime().getBlazeModule(RemoteModule.class);
    if (remoteModule == null) {
      logger.atWarning().log("configure: RemoteModule not found");
      return;
    }

    com.google.devtools.build.lib.remote.common.RemoteCacheClient remoteCacheClient =
        remoteModule.getRemoteCacheClient();
    if (remoteCacheClient == null) {
      logger.atInfo().log("configure: remote cache not configured, skipping remote analysis cache");
      return;
    }

    com.google.devtools.build.lib.remote.util.DigestUtil digestUtil = remoteModule.getDigestUtil();
    if (digestUtil == null) {
      logger.atWarning().log("configure: digestUtil is null");
      return;
    }

    logger.atInfo().log("Configuring remote analysis cache supplier with gRPC backend");
    this.store = new GrpcFingerprintValueStore(remoteCacheClient, digestUtil);
    this.storeFuture = immediateFuture(store);

    LongVersionGetter versionGetter = env.getVersionGetter();
    this.client =
        new LocalAnalysisCacheClient(
            store, env.getDirectories().getWorkspace().getFileSystem(), versionGetter, directExecutor());
    this.clientFuture = immediateFuture(client);
  }

  @Override
  @Nullable
  public ListenableFuture<? extends FingerprintValueStore> getFingerprintValueStore() {
    return storeFuture;
  }

  @Override
  @Nullable
  public ListenableFuture<? extends RemoteAnalysisCacheClient> getAnalysisCacheClient() {
    return clientFuture;
  }

  @Override
  public void resetCommandState() {
    this.store = null;
    this.storeFuture = null;
    this.client = null;
    this.clientFuture = null;
  }

  @Override
  public void blazeShutdown() {}
}
