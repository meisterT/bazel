// Copyright 2024 The Bazel Authors. All rights reserved.
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

import static com.google.common.util.concurrent.Futures.immediateFuture;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheMode;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheStorageType;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingConfig;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesSupplier;
import com.google.devtools.build.lib.skyframe.serialization.analysis.LocalAnalysisCacheClient;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheClient;
import com.google.devtools.build.lib.skyframe.serialization.analysis.LongVersionGetterTestInjection;
import com.google.devtools.build.lib.util.TestType;
import com.google.devtools.build.lib.versioning.ContentHashVersionGetter;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import com.google.errorprone.annotations.ForOverride;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** A {@link BlazeModule} to store Skyframe serialization lifecycle hooks. */
public class SerializationModule extends BlazeModule {

  private BlazeDirectories directories;
  private RemoteAnalysisCachingServicesSupplier remoteAnalysisCachingServicesSupplier;
  @Nullable private PersistentRemoteAnalysisCachingServicesSupplier supplier;

  @Override
  public void workspaceInit(
      BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
    if (!directories.inWorkspace()) {
      // Serialization only works when the Bazel server is invoked from a workspace.
      // Counter-example: invoking the Bazel server outside of a workspace to generate/dump
      // documentation HTML.
      return;
    }
    this.directories = directories;
    // This is injected as a callback instead of evaluated eagerly to avoid forcing the somewhat
    // expensive AutoRegistry.get call on clients that don't require it.
    builder.setAnalysisCodecRegistrySupplier(
        getAnalysisCodecRegistrySupplier(runtime, directories));

    this.remoteAnalysisCachingServicesSupplier = getAnalysisCachingServicesSupplier(runtime);
    if (this.remoteAnalysisCachingServicesSupplier instanceof PersistentRemoteAnalysisCachingServicesSupplier) {
      this.supplier = (PersistentRemoteAnalysisCachingServicesSupplier) this.remoteAnalysisCachingServicesSupplier;
    }
    builder.setRemoteAnalysisCachingServicesSupplier(this.remoteAnalysisCachingServicesSupplier);
    builder.setFingerprinterForAnalysisCaching(getFingerprinterForAnalysisCaching());
  }

  @Override
  public void beforeCommand(CommandEnvironment env) {
    LongVersionGetter versionGetter;
    if (TestType.isInTest()) {
      versionGetter = LongVersionGetterTestInjection.getVersionGetterForTesting();
    } else {
      versionGetter = new ContentHashVersionGetter();
    }
    env.setVersionGetter(versionGetter);
    if (this.supplier != null) {
      this.supplier.beforeCommand(env);
    }
  }

  @Override
  public void commandComplete() {
    if (remoteAnalysisCachingServicesSupplier != null) {
      remoteAnalysisCachingServicesSupplier.resetCommandState();
    }
  }

  @Override
  public void blazeShutdown() {
    if (remoteAnalysisCachingServicesSupplier != null) {
      remoteAnalysisCachingServicesSupplier.blazeShutdown();
    }
  }

  @ForOverride
  protected Supplier<ObjectCodecRegistry> getAnalysisCodecRegistrySupplier(
      BlazeRuntime runtime, BlazeDirectories directories) {
    return () ->
        SerializationRegistrySetupHelpers.initializeAnalysisCodecRegistryBuilder(
                runtime.getRuleClassProvider(),
                SerializationRegistrySetupHelpers.makeReferenceConstants(
                    directories,
                    runtime.getRuleClassProvider(),
                    directories.getWorkspace().getBaseName()))
            .build();
  }

  @ForOverride
  protected RemoteAnalysisCachingServicesSupplier getAnalysisCachingServicesSupplier(
      BlazeRuntime runtime) {
    return new PersistentRemoteAnalysisCachingServicesSupplier(this.directories);
  }

  @ForOverride
  protected Fingerprinter getFingerprinterForAnalysisCaching() {
    return FingerprintValueService.NONPROD_FINGERPRINTER;
  }

  /** A supplier that uses a persistent disk fingerprint value store. */
  private static final class PersistentRemoteAnalysisCachingServicesSupplier
      implements RemoteAnalysisCachingServicesSupplier {
    private final BlazeDirectories directories;
    @Nullable private FingerprintValueStore store;
    @Nullable private ListenableFuture<? extends FingerprintValueStore> storeFuture;
    @Nullable private LocalAnalysisCacheClient client;
    @Nullable private ListenableFuture<? extends RemoteAnalysisCacheClient> clientFuture;
    @Nullable private CommandEnvironment env;

    private PersistentRemoteAnalysisCachingServicesSupplier(BlazeDirectories directories) {
      this.directories = directories;
    }

    public void beforeCommand(CommandEnvironment env) {
      this.env = env;
      if (client != null) {
        client.clearValidationCache();
      }
    }

    @Override
    public void configure(
        RemoteAnalysisCachingConfig config, @Nullable ClientId clientId, String buildId)
        throws com.google.devtools.build.lib.util.SerializedAbruptExitException {
      if (config.mode() != RemoteAnalysisCacheMode.OFF && storeFuture == null) {
        LongVersionGetter versionGetter = env != null ? env.getVersionGetter() : new ContentHashVersionGetter();
        if (config.storageType() == RemoteAnalysisCacheStorageType.HDD
            || config.storageType() == RemoteAnalysisCacheStorageType.BOTH) {
          com.google.devtools.build.lib.vfs.Path cacheDir =
              directories.getOutputBase().getRelative("skycache");
          this.store = new DiskFingerprintValueStore(cacheDir);
          this.storeFuture = immediateFuture(store);
          this.client = new LocalAnalysisCacheClient(
              store,
              directories.getWorkspace().getFileSystem(),
              versionGetter,
              directExecutor());
          this.clientFuture = immediateFuture(client);
        } else {
          this.store = new InMemoryFingerprintValueStore();
          this.storeFuture = immediateFuture(store);
          this.client = new LocalAnalysisCacheClient(
              store,
              directories.getWorkspace().getFileSystem(),
              versionGetter,
              directExecutor());
          this.clientFuture = immediateFuture(client);
        }
      }
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
      // We keep the store across commands to avoid recreating the executor.
    }

    @Override
    public void blazeShutdown() {
      if (store != null) {
        store.shutdown();
      }
    }
  }
}
