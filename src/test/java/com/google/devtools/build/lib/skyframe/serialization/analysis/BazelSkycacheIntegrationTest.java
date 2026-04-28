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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.LongVersionGetterTestInjection.injectVersionGetterForTesting;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.SerializationModule;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BazelSkycacheIntegrationTest extends SkycacheIntegrationTestBase {
  private final LongVersionGetter versionGetter = mock(LongVersionGetter.class);
  private final FailingFingerprintValueStore failingStore = new FailingFingerprintValueStore();

  @Before
  public void injectVersionGetter() {
    injectVersionGetterForTesting(versionGetter);
    addOptions("--disk_cache=" + getWorkspace().getRelative("disk_cache").getPathString());
  }

  private static class FailingFingerprintValueStore implements FingerprintValueStore {
    private final FingerprintValueStore delegate =
        new com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.InMemoryFingerprintValueStore(true);
    private final AtomicBoolean shouldFail = new AtomicBoolean();
    private final AtomicInteger failCounter = new AtomicInteger();
    private final AtomicReference<KeyBytesProvider> lastFailedKey = new AtomicReference<>();

    private void failNextPut() {
      shouldFail.set(true);
    }

    private int getFailCounter() {
      return failCounter.get();
    }

    private KeyBytesProvider getFailedKey() {
      return lastFailedKey.get();
    }

    public FingerprintValueStore getDelegate() {
      return delegate;
    }

    @Override
    public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
      if (shouldFail.getAndSet(false)) {
        failCounter.getAndIncrement();
        lastFailedKey.set(fingerprint);
        return WriteStatuses.immediateFailedWriteStatus(
            new IOException("Simulated write failure for " + fingerprint));
      }
      System.out.println("DEBUG: FailingStore.put: key=" + com.google.common.io.BaseEncoding.base16().lowerCase().encode(fingerprint.toBytes()) + " size=" + serializedBytes.length);
      return delegate.put(fingerprint, serializedBytes);
    }

    @Override
    public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) throws IOException {
      ListenableFuture<byte[]> result = delegate.get(fingerprint);
      try {
        byte[] bytes = result.get();
        System.out.println("DEBUG: FailingStore.get: key=" + com.google.common.io.BaseEncoding.base16().lowerCase().encode(fingerprint.toBytes()) + " found=" + (bytes != null && bytes.length > 0));
      } catch (Exception e) {}
      return result;
    }
  }

  private class ModuleWithOverrides extends SerializationModule {
    @Override
    protected RemoteAnalysisCachingServicesSupplier getAnalysisCachingServicesSupplier() {
      return new TestServicesSupplier(failingStore);
    }
  }

  private static final class SimpleSkycacheMetadataParams implements com.google.devtools.build.lib.skyframe.serialization.SkycacheMetadataParams {
    private long clNumber;
    private String bazelVersion;
    private java.util.Collection<String> targets;
    private boolean useFakeStampData;
    private java.util.Map<String, String> userOptions;
    private java.util.Set<String> projectSclOptions;
    private java.util.Set<String> configOptions;
    private String configurationHash;

    @Override
    public void init(
        long clNumber,
        String bazelVersion,
        java.util.Collection<String> targets,
        boolean useFakeStampData,
        java.util.Map<String, String> userOptions,
        java.util.Set<String> projectSclOptions) {
      this.clNumber = clNumber;
      this.bazelVersion = bazelVersion;
      this.targets = targets;
      this.useFakeStampData = useFakeStampData;
      this.userOptions = userOptions;
      this.projectSclOptions = projectSclOptions;
    }

    @Override
    public void setOriginalConfigurationOptions(java.util.Set<String> configOptions) {
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
    public java.util.Collection<String> getTargets() {
      return targets != null ? targets : com.google.common.collect.ImmutableList.of();
    }

    @Override
    public java.util.Collection<String> getConfigFlags() {
      return configOptions != null ? configOptions : com.google.common.collect.ImmutableList.of();
    }
  }

  private static class TestServicesSupplier implements RemoteAnalysisCachingServicesSupplier {
    private final ListenableFuture<FingerprintValueService> wrappedService;
    private final com.google.devtools.build.lib.skyframe.serialization.SkycacheMetadataParams metadataParams = new SimpleSkycacheMetadataParams();

    private TestServicesSupplier(FailingFingerprintValueStore failingStore) {
      this.wrappedService =
          immediateFuture(
              new FingerprintValueService(
                  newSingleThreadExecutor(),
                  failingStore,
                  new FingerprintValueCache(FingerprintValueCache.SyncMode.NOT_LINKED),
                  FingerprintValueService.NONPROD_FINGERPRINTER));
    }

    @Override
    public void configure(
        RemoteAnalysisCachingOptions cachingOptions,
        ClientId clientId,
        String buildId,
        com.google.devtools.common.options.OptionsParsingResult optionsResult,
        com.google.devtools.build.lib.analysis.BlazeDirectories directories)
        throws AbruptExitException {
      var skycacheOptions = optionsResult.getOptions(SkycacheOptions.class);
      if (skycacheOptions != null) {
        var skycacheFlags = skycacheOptions.getSkycache();
        if (skycacheFlags.contains("read")) {
          cachingOptions.setMode(com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions.RemoteAnalysisCacheMode.DOWNLOAD);
        } else if (skycacheFlags.contains("write")) {
          cachingOptions.setMode(com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions.RemoteAnalysisCacheMode.UPLOAD);
        }
      }
    }

    @Override
    public ListenableFuture<FingerprintValueService> getFingerprintValueService() {
      return wrappedService;
    }

    @Override
    public com.google.devtools.build.lib.skyframe.serialization.SkycacheMetadataParams getSkycacheMetadataParams() {
      return metadataParams;
    }

    @Override
    public void resetCommandState() {}
  }

  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder()
        .addBlazeModule(new ModuleWithOverrides())
        .addBlazeModule(
            new com.google.devtools.build.lib.runtime.BlazeModule() {
              @Override
              public Iterable<Class<? extends com.google.devtools.common.options.OptionsBase>> getCommandOptions(String commandName) {
                return com.google.common.collect.ImmutableList.of(com.google.devtools.build.lib.remote.options.RemoteOptions.class);
              }
            });
  }

  @Test
  public void buildCommand_uploadsFrontierBytesWithUploadMode() throws Exception {
    setupScenarioWithAspects();
    assertUploadSuccess("//bar:one");

    var listener = getCommandEnvironment().getRemoteAnalysisCachingEventListener();
    assertThat(listener.getSerializedKeysCount()).isAtLeast(9); // for Bazel
    assertThat(listener.getSkyfunctionCounts().count(SkyFunctions.CONFIGURED_TARGET))
        .isAtLeast(9); // for Bazel
  }

  @Test
  public void buildCommand_withSkycacheRead_hitsCache() throws Exception {
    setupScenarioWithAspects();
    addOptions("--notrim_test_configuration");
    
    // 1. Write to cache
    addOptions("--experimental_skycache=write");
    addOptions("--experimental_remote_analysis_cache_mode=UPLOAD");
    buildTarget("//bar:one");
    
    var skycachePath = getWorkspace().getRelative("disk_cache/skycache");
    System.out.println("DEBUG: Skycache path exists after write: " + skycachePath.exists());
    if (skycachePath.exists()) {
      System.out.println("DEBUG: Skycache directory entries after write: " + skycachePath.getDirectoryEntries());
    }

    // 2. Simulate clean state by clearing graph
    getSkyframeExecutor().resetEvaluator();

    // 3. Read from cache
    addOptions("--experimental_skycache=read");
    addOptions("--experimental_remote_analysis_cache_mode=DOWNLOAD");

    try {
      System.setProperty("bazel.skycache.test.modified_files", "");
      buildTarget("//bar:one");

      // 4. Verify cache hit
      var listener = getCommandEnvironment().getRemoteAnalysisCachingEventListener();
      assertThat(listener.getCacheHits()).isNotEmpty();
      assertThat(listener.getCacheMisses()).isEmpty();
    } finally {
      System.clearProperty("bazel.skycache.test.modified_files");
    }
  }

  @Test
  public void buildCommand_withWriteFailure_reportsErrorAndCompletes() throws Exception {
    setupScenarioWithAspects();

    failingStore.failNextPut();

    addOptions(UPLOAD_MODE_OPTION);
    var thrown = assertThrows(AbruptExitException.class, () -> buildTarget("//bar:one"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Simulated write failure for " + failingStore.getFailedKey());

    assertThat(failingStore.getFailCounter()).isEqualTo(1);
    assertContainsEvent("Simulated write failure for " + failingStore.getFailedKey());
  }

  @Test
  public void buildCommand_withSkycacheWrite_populatesCache() throws Exception {
    setupScenarioWithAspects();
    addOptions("--experimental_skycache=write");
    addOptions("--experimental_remote_analysis_cache_mode=UPLOAD");
    
    buildTarget("//bar:one");
    
    var inMemoryStore = (FingerprintValueStore.InMemoryFingerprintValueStore) failingStore.getDelegate();
    assertThat(inMemoryStore.keys()).isNotEmpty();
  }

  @Test
  public void buildCommand_withSkycacheRead_withModification_hitsCacheForUnrelatedFiles() throws Exception {
    setupScenarioWithAspects();
    addOptions("--notrim_test_configuration");
    
    // 1. Write to cache
    addOptions("--experimental_skycache=write");
    addOptions("--experimental_remote_analysis_cache_mode=UPLOAD");
    buildTarget("//bar:one");
    
    // 2. Modify a file NOT in dependency list
    // Assume a dummy file for this test
    write("unrelated_file.txt", "content");
    
    // 3. Simulate clean state by clearing graph
    getSkyframeExecutor().resetEvaluator();
    
    // 4. Read from cache
    addOptions("--experimental_skycache=read");
    addOptions("--experimental_remote_analysis_cache_mode=DOWNLOAD");
    
    try {
      System.setProperty("bazel.skycache.test.modified_files", "unrelated_file.txt");
      buildTarget("//bar:one");

      // 5. Verify cache hit (since modification was unrelated, all dependencies are valid and hit cache)
      var listener = getCommandEnvironment().getRemoteAnalysisCachingEventListener();
      assertThat(listener.getCacheHits()).isNotEmpty();
      assertThat(listener.getCacheMisses()).isEmpty();
    } finally {
      System.clearProperty("bazel.skycache.test.modified_files");
    }
  }

  @Test
  public void buildCommand_withSkycacheRead_withModification_rejectsCacheForRelatedFiles() throws Exception {
    setupScenarioWithAspects();
    
    // 1. Write to cache
    addOptions("--experimental_skycache=write");
    addOptions("--experimental_remote_analysis_cache_mode=UPLOAD");
    buildTarget("//bar:one");
    
    // 2. Modify a file IN dependency list with a syntax error
    write("bar/BUILD", "this is invalid syntax that should fail the build!");
    
    // 3. Simulate clean state by clearing graph
    getSkyframeExecutor().resetEvaluator();
    
    // 4. Read from cache
    addOptions("--experimental_skycache=read");
    addOptions("--experimental_remote_analysis_cache_mode=DOWNLOAD");
    
    // 5. Verify correctness: Since bar/BUILD has a syntax error and cache invalidation is triggered,
    // the build must FAIL. If invalidation was broken (and stale cache was incorrectly hit), the build would succeed.
    try {
      System.setProperty("bazel.skycache.test.modified_files", "bar/BUILD");
      assertThrows(Exception.class, () -> buildTarget("//bar:one"));
    } finally {
      System.clearProperty("bazel.skycache.test.modified_files");
    }
  }

  @Test
  public void buildCommand_withSkycacheRead_withNewFileMatchingGlob_recalculatesGlobCorrectly() throws Exception {
    // 1. Setup workspace with a glob
    write("glob_pkg/BUILD",
        "filegroup(",
        "    name = 'group',",
        "    srcs = glob(['*.txt']),",
        ")");
    write("glob_pkg/a.txt", "content a");

    // 2. Run build & Write to cache (using automatic key resolution)
    addOptions("--experimental_skycache=write");
    addOptions("--experimental_remote_analysis_cache_mode=UPLOAD");
    buildTarget("//glob_pkg:group");

    // 3. Add a new file that matches the glob
    write("glob_pkg/b.txt", "content b");

    // 4. Simulate clean state by resetting the evaluator
    getSkyframeExecutor().resetEvaluator();

    // 5. Read from cache (automatic key resolution)
    addOptions("--experimental_skycache=read");
    addOptions("--experimental_remote_analysis_cache_mode=DOWNLOAD");
    try {
      System.setProperty("bazel.skycache.test.modified_files", "glob_pkg/b.txt");
      buildTarget("//glob_pkg:group");
    } finally {
      System.clearProperty("bazel.skycache.test.modified_files");
    }

    // 6. Verify correctness: The target must now successfully contain BOTH a.txt and b.txt.
    // In SkycacheIntegrationTestBase, we can fetch the configured target to inspect its output.
    var configuredTarget = getConfiguredTarget("//glob_pkg:group");
    assertThat(configuredTarget).isNotNull();
    // We can assert on the files inside the filegroup's filesToBuild.
    var files = getFilesToBuild(configuredTarget);
    assertThat(files.toList().stream().map(artifact -> artifact.getRootRelativePath().getBaseName()))
        .containsExactly("a.txt", "b.txt");
  }
}
