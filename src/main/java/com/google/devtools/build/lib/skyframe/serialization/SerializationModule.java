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
import static java.util.concurrent.ForkJoinPool.commonPool;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.skyframe.serialization.analysis.DiskFingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.analysis.LocalSkycacheStorage;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions.RemoteAnalysisCacheMode;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesSupplier;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId;
import com.google.devtools.build.lib.skyframe.serialization.analysis.SkycacheOptions;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteAnalysisCaching;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.devtools.common.options.OptionsBase;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.ForOverride;
import java.io.IOException;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** A {@link BlazeModule} to store Skyframe serialization lifecycle hooks. */
public class SerializationModule extends BlazeModule {

  private RemoteAnalysisCachingServicesSupplier remoteAnalysisCachingServicesSupplier;

  @Override
  public void workspaceInit(
      BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
    if (!directories.inWorkspace()) {
      // Serialization only works when the Bazel server is invoked from a workspace.
      // Counter-example: invoking the Bazel server outside of a workspace to generate/dump
      // documentation HTML.
      return;
    }
    // This is injected as a callback instead of evaluated eagerly to avoid forcing the somewhat
    // expensive AutoRegistry.get call on clients that don't require it.
    builder.setAnalysisCodecRegistrySupplier(
        getAnalysisCodecRegistrySupplier(runtime, directories));

    remoteAnalysisCachingServicesSupplier = getAnalysisCachingServicesSupplier();
    builder.setRemoteAnalysisCachingServicesSupplier(remoteAnalysisCachingServicesSupplier);
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
  protected RemoteAnalysisCachingServicesSupplier getAnalysisCachingServicesSupplier() {
    return InMemoryRemoteAnalysisCachingServicesSupplier.INSTANCE;
  }

  /** A supplier that uses an in-memory fingerprint value service. */
  private static final class InMemoryRemoteAnalysisCachingServicesSupplier
      implements RemoteAnalysisCachingServicesSupplier {
    private static final InMemoryRemoteAnalysisCachingServicesSupplier INSTANCE =
        new InMemoryRemoteAnalysisCachingServicesSupplier();

    private OptionsParsingResult optionsResult;
    private BlazeDirectories directories;
    private ListenableFuture<FingerprintValueService> serviceFuture;

    @Override
    public void configure(
        RemoteAnalysisCachingOptions cachingOptions,
        @Nullable ClientId clientId,
        String buildId,
        OptionsParsingResult optionsResult,
        BlazeDirectories directories)
        throws AbruptExitException {
      this.optionsResult = optionsResult;
      this.directories = directories;

      // Check if skycache is enabled for read or write
      var skycacheOptions = optionsResult.getOptions(SkycacheOptions.class);
      if (skycacheOptions != null) {
        var skycacheFlags = skycacheOptions.getSkycache();
        boolean isRead = skycacheFlags.contains("read");
        boolean isWrite = skycacheFlags.contains("write");

        if (isRead) {
          cachingOptions.setMode(RemoteAnalysisCacheMode.DOWNLOAD);
        } else if (isWrite) {
          cachingOptions.setMode(RemoteAnalysisCacheMode.UPLOAD);
        }
        if (isRead || isWrite) {
          var remoteOptions = optionsResult.getOptions(RemoteOptions.class);
          System.out.println("DEBUG: SerializationModule: remoteOptions=" + remoteOptions);
          if (remoteOptions != null) {
            System.out.println("DEBUG: SerializationModule: diskCache=" + remoteOptions.getDiskCache());
          }
          if (remoteOptions != null && remoteOptions.getDiskCache() != null) {
            // Using the same logic as RemoteModule to resolve the path relative to client working directory
            // which is more correct than always using workspace.
            var diskCacheStr = remoteOptions.getDiskCache().toString();
            var diskCacheFragment = PathFragment.create(diskCacheStr);
            Path diskCachePath;
            if (diskCacheFragment.isAbsolute()) {
              diskCachePath = directories.getWorkspace().getFileSystem().getPath(diskCacheFragment);
            } else {
              diskCachePath = directories.getWorkspace().getRelative(diskCacheFragment);
            }
            var skycachePath = diskCachePath.getChild("skycache");
            try {
              var storage = new LocalSkycacheStorage(skycachePath);
              var store = new DiskFingerprintValueStore(storage);
              var service = new FingerprintValueService(
                  commonPool(),
                  store,
                  new FingerprintValueCache(FingerprintValueCache.SyncMode.NOT_LINKED),
                  FingerprintValueService.NONPROD_FINGERPRINTER);
              this.serviceFuture = immediateFuture(service);
              return;
            } catch (IOException e) {
              throw new AbruptExitException(DetailedExitCode.of(FailureDetail.newBuilder()
                  .setMessage("Failed to create skycache storage: " + e.getMessage())
                  .setRemoteAnalysisCaching(RemoteAnalysisCaching.newBuilder().setCode(RemoteAnalysisCaching.Code.CANNOT_OPEN_LOG_FILE))
                  .build()));
            }
          }
        }
      }

      // Fallback to in-memory store
      var service = new FingerprintValueService(
          commonPool(),
          FingerprintValueStore.inMemoryStore(),
          new FingerprintValueCache(FingerprintValueCache.SyncMode.NOT_LINKED),
          FingerprintValueService.NONPROD_FINGERPRINTER);
      this.serviceFuture = immediateFuture(service);
    }

    @Override
    public ListenableFuture<FingerprintValueService> getFingerprintValueService() {
      return serviceFuture != null ? serviceFuture : immediateFuture(null);
    }

    private static final class SimpleSkycacheMetadataParams implements SkycacheMetadataParams {
      private long clNumber;
      private String bazelVersion;
      private Collection<String> targets;
      private boolean useFakeStampData;
      private Map<String, String> userOptions;
      private Set<String> projectSclOptions;
      private Set<String> configOptions;
      private String configurationHash;

      @Override
      public void init(
          long clNumber,
          String bazelVersion,
          Collection<String> targets,
          boolean useFakeStampData,
          Map<String, String> userOptions,
          Set<String> projectSclOptions) {
        this.clNumber = clNumber;
        this.bazelVersion = bazelVersion;
        this.targets = targets;
        this.useFakeStampData = useFakeStampData;
        this.userOptions = userOptions;
        this.projectSclOptions = projectSclOptions;
      }

      @Override
      public void setOriginalConfigurationOptions(Set<String> configOptions) {
        this.configOptions = configOptions;
      }

      @Override
      public void setConfigurationHash(String configurationHash) {
        this.configurationHash = configurationHash;
      }

      @Override
      public long getEvaluatingVersion() {
        return clNumber;
      }

      @Override
      public String getConfigurationHash() {
        return configurationHash;
      }

      @Override
      public String getBazelVersion() {
        return bazelVersion;
      }

      @Override
      public boolean getUseFakeStampData() {
        return useFakeStampData;
      }

      @Override
      public Collection<String> getTargets() {
        return targets != null ? targets : ImmutableList.of();
      }

      @Override
      public Collection<String> getConfigFlags() {
        return configOptions != null ? configOptions : ImmutableList.of();
      }
    }

    private final SkycacheMetadataParams metadataParams = new SimpleSkycacheMetadataParams();

    @Override
    public SkycacheMetadataParams getSkycacheMetadataParams() {
      return metadataParams;
    }

    @Override
    public void resetCommandState() {}

    @Override
    public void blazeShutdown() {}
  }

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(String commandName) {
    return commandName.equals("build")
        ? ImmutableList.of(SkycacheOptions.class)
        : ImmutableList.of();
  }
}
