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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.NshNodeEntry;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.skyframe.serialization.SerializationModule;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesSupplier;
import com.google.devtools.build.lib.skyframe.serialization.analysis.LocalAnalysisCacheClient;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.LongVersionGetterTestInjection.injectVersionGetterForTesting;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BazelSkycacheCorrectnessTest extends BuildIntegrationTestCase {

  private static final LongVersionGetter DUMMY_VERSION_GETTER = new LongVersionGetter() {
    @Override
    public long getFilePathOrSymlinkVersion(Path path) {
      return 12345L;
    }
    @Override
    public long getDirectoryListingVersion(Path path) {
      return 12345L;
    }
    @Override
    public long getNonexistentPathVersion(Path path) {
      return -1L; // MINIMAL
    }
  };

  @Before
  public void injectVersionGetter() {
    injectVersionGetterForTesting(DUMMY_VERSION_GETTER);
  }

  @After
  public final void tearDown() throws Exception {
  }

  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder()
        .addBlazeModule(new SerializationModule())
        .addBlazeModule(
            new com.google.devtools.build.lib.runtime.BlazeModule() {
              @Override
              public void workspaceInit(
                  com.google.devtools.build.lib.runtime.BlazeRuntime runtime,
                  com.google.devtools.build.lib.analysis.BlazeDirectories directories,
                  com.google.devtools.build.lib.runtime.WorkspaceBuilder builder) {
                builder.allowExternalRepositories(false);
              }
            });
  }

  @Test
  public void testNshPropagation() throws Exception {
    write("pkg/BUILD",
        "genrule(",
        "    name = 'hello',",
        "    srcs = ['hello.txt'],",
        "    outs = ['hello.out'],",
        "    cmd = 'cat $(SRCS) > $(OUTS)',",
        ")",
        "genrule(",
        "    name = 'unrelated',",
        "    srcs = ['unrelated.txt'],",
        "    outs = ['unrelated.out'],",
        "    cmd = 'cat $(SRCS) > $(OUTS)',",
        ")");
    write("pkg/hello.txt", "hello");
    write("pkg/unrelated.txt", "unrelated");

    addOptions("--experimental_remote_analysis_cache_mode=dump_upload_manifest_only");

    // 1. First build
    buildTarget("//pkg:hello");
    buildTarget("//pkg:unrelated");

    InMemoryMemoizingEvaluator evaluator = (InMemoryMemoizingEvaluator) getSkyframeExecutor().getEvaluator();
    InMemoryGraph graph = evaluator.getInMemoryGraph();

    // Check all nodes are NshNodeEntry
    for (InMemoryNodeEntry entry : graph.getAllNodeEntries()) {
      assertThat(entry).isInstanceOf(NshNodeEntry.class);
    }

    ConfiguredTargetKey helloKey = null;
    ConfiguredTargetKey unrelatedKey = null;
    SkyKey helloFileKey = null;
    
    NshNodeEntry helloEntry = null;
    NshNodeEntry unrelatedEntry = null;
    NshNodeEntry helloFileEntry = null;
    for (InMemoryNodeEntry entry : graph.getAllNodeEntries()) {
      SkyKey key = entry.getKey();
      if (key instanceof ConfiguredTargetKey) {
        ConfiguredTargetKey ctKey = (ConfiguredTargetKey) key;
        if (ctKey.getLabel().toString().equals("//pkg:hello")) {
          if (graph.getIfPresent(ActionLookupData.create(ctKey, 0)) != null) {
            helloKey = ctKey;
            helloEntry = (NshNodeEntry) entry;
          }
        } else if (ctKey.getLabel().toString().equals("//pkg:unrelated")) {
          if (graph.getIfPresent(ActionLookupData.create(ctKey, 0)) != null) {
            unrelatedKey = ctKey;
            unrelatedEntry = (NshNodeEntry) entry;
          }
        }
      } else if (key.functionName().getName().equals("FILE_STATE")) {
        RootedPath rp = (RootedPath) key.argument();
        if (rp.getRootRelativePath().getPathString().equals("pkg/hello.txt")) {
          helloFileKey = key;
          helloFileEntry = (NshNodeEntry) entry;
        }
      }
    }

    assertThat(helloEntry).isNotNull();
    assertThat(unrelatedEntry).isNotNull();
    assertThat(helloFileEntry).isNotNull();


    ActionLookupData helloActionKey = ActionLookupData.create(helloKey, 0);
    NshNodeEntry helloActionEntry = (NshNodeEntry) graph.getIfPresent(helloActionKey);
    assertThat(helloActionEntry).isNotNull();
    long initialHelloActionLow = helloActionEntry.getNshLow();
    long initialHelloActionHigh = helloActionEntry.getNshHigh();

    ActionLookupData unrelatedActionKey = ActionLookupData.create(unrelatedKey, 0);
    NshNodeEntry unrelatedActionEntry = (NshNodeEntry) graph.getIfPresent(unrelatedActionKey);
    assertThat(unrelatedActionEntry).isNotNull();
    long initialUnrelatedActionLow = unrelatedActionEntry.getNshLow();
    long initialUnrelatedActionHigh = unrelatedActionEntry.getNshHigh();

    long initialHelloLow = helloEntry.getNshLow();
    long initialHelloHigh = helloEntry.getNshHigh();
    
    long initialUnrelatedLow = unrelatedEntry.getNshLow();
    long initialUnrelatedHigh = unrelatedEntry.getNshHigh();
    
    long initialFileLow = helloFileEntry.getNshLow();
    long initialFileHigh = helloFileEntry.getNshHigh();

    // 2. Modify a source file
    write("pkg/hello.txt", "Hello Universe");

    // Invalidate the file so Skyframe picks up the change
    Path helloTxt = directories.getWorkspace().getRelative("pkg/hello.txt");
    System.out.println("Workspace: " + directories.getWorkspace());
    System.out.println("helloTxt: " + helloTxt);
    System.out.println("Relative: " + helloTxt.relativeTo(directories.getWorkspace()));

    getSkyframeExecutor().invalidateFilesUnderPathForTesting(
        events.reporter(),
        ModifiedFileSet.builder().modify(helloTxt.relativeTo(directories.getWorkspace())).build(),
        Root.fromPath(directories.getWorkspace()));

    InMemoryNodeEntry helloFileEntryAfterInvalidation = graph.getIfPresent(helloFileKey);
    System.out.println("Is hello.txt entry dirty after invalidation? " + helloFileEntryAfterInvalidation.isDirty());

    // Build again
    buildTarget("//pkg:hello");
    buildTarget("//pkg:unrelated");

    NshNodeEntry newHelloEntry = (NshNodeEntry) graph.getIfPresent(helloKey);
    NshNodeEntry newUnrelatedEntry = (NshNodeEntry) graph.getIfPresent(unrelatedKey);
    NshNodeEntry newHelloFileEntry = (NshNodeEntry) graph.getIfPresent(helloFileKey);
    
    NshNodeEntry newHelloActionEntry = (NshNodeEntry) graph.getIfPresent(helloActionKey);
    NshNodeEntry newUnrelatedActionEntry = (NshNodeEntry) graph.getIfPresent(unrelatedActionKey);

    // Verify FileState NSH changed
    assertThat(newHelloFileEntry.getNshLow() != initialFileLow || newHelloFileEntry.getNshHigh() != initialFileHigh).isTrue();

    // Verify Action NSH changed
    assertThat(newHelloActionEntry.getNshLow() != initialHelloActionLow || newHelloActionEntry.getNshHigh() != initialHelloActionHigh).isTrue();

    // Verify ConfiguredTarget NSH stayed the same (analysis cached)
    assertThat(newHelloEntry.getNshLow()).isEqualTo(initialHelloLow);
    assertThat(newHelloEntry.getNshHigh()).isEqualTo(initialHelloHigh);

    // Verify Unrelated ConfiguredTarget NSH stayed the same
    assertThat(newUnrelatedEntry.getNshLow()).isEqualTo(initialUnrelatedLow);
    assertThat(newUnrelatedEntry.getNshHigh()).isEqualTo(initialUnrelatedHigh);
    
    // Verify Unrelated Action NSH stayed the same
    assertThat(newUnrelatedActionEntry.getNshLow()).isEqualTo(initialUnrelatedActionLow);
    assertThat(newUnrelatedActionEntry.getNshHigh()).isEqualTo(initialUnrelatedActionHigh);
  }

  @Test
  public void testDiskCacheIntegration() throws Exception {
    injectVersionGetterForTesting(DUMMY_VERSION_GETTER);

    // Configure Skycache with HDD storage
    addOptions(
        "--experimental_remote_analysis_cache_mode=upload",
        "--experimental_remote_analysis_cache_storage=HDD",
        "--experimental_skycache_analysis_only=true"
    );

    // Create a simple target
    write("pkg/BUILD",
        "genrule(",
        "  name = 'hello',",
        "  outs = ['hello.out'],",
        "  cmd = 'echo \"Hello World\" > $@',",
        ")");

    // Build the target. This should trigger serialization and upload to disk cache.
    buildTarget("//pkg:hello");

    // Verify files were written to the skycache directory
    Path cacheDir = directories.getOutputBase().getRelative("skycache");
    assertThat(cacheDir.exists()).isTrue();
    assertThat(cacheDir.isDirectory()).isTrue();

    var files = cacheDir.getDirectoryEntries();
    assertThat(files).isNotEmpty();

    // Print the files for debugging
    System.out.println("DEBUG Skycache: Files in disk cache:");
    for (Path file : files) {
      System.out.println("  " + file.getBaseName() + " (size: " + file.getFileSize() + ")");
    }
  }

  @Test
  public void testRamCacheIntegration() throws Exception {
    injectVersionGetterForTesting(DUMMY_VERSION_GETTER);

    // 1. Build in UPLOAD mode
    addOptions(
        "--experimental_remote_analysis_cache_mode=upload",
        "--experimental_skycache_analysis_only=true"
    );
    write("pkg/BUILD",
        "genrule(",
        "  name = 'hello',",
        "  outs = ['hello.out'],",
        "  cmd = 'echo \"Hello World\" > $@',",
        ")");
    buildTarget("//pkg:hello");

    // 2. Clean to reset in-memory Skyframe graph
    clean();

    // 3. Build in DOWNLOAD mode. This should read from the RAM cache.
    addOptions(
        "--experimental_remote_analysis_cache_mode=download",
        "--experimental_skycache_analysis_only=true"
    );
    buildTarget("//pkg:hello");

    // Verify we got cache hits
    RemoteAnalysisCachingServicesSupplier supplier =
        getRuntime().getWorkspace().remoteAnalysisCachingServicesSupplier();
    LocalAnalysisCacheClient client =
        (LocalAnalysisCacheClient) supplier.getAnalysisCacheClient().get();
    
    System.out.println("DEBUG Skycache: Cache hits in test: " + client.getCacheHits());
    assertThat(client.getCacheHits()).isGreaterThan(0);
  }

  @Test
  public void testRamCacheInvalidation() throws Exception {
    MockLongVersionGetter mockVersionGetter = new MockLongVersionGetter(DUMMY_VERSION_GETTER);
    injectVersionGetterForTesting(mockVersionGetter);

    // 1. Build in UPLOAD mode
    addOptions(
        "--experimental_remote_analysis_cache_mode=upload",
        "--experimental_skycache_analysis_only=true"
    );
    write("pkg/BUILD",
        "genrule(",
        "  name = 'hello',",
        "  outs = ['hello.out'],",
        "  cmd = 'echo \"Hello World\" > $@',",
        ")");
    buildTarget("//pkg:hello");

    // 2. Clean to reset in-memory Skyframe graph
    clean();

    // 3. Mark pkg/BUILD as dirty in mock
    mockVersionGetter.setFileOverride(directories.getWorkspace().getRelative("pkg/BUILD"), 99999L);

    // 4. Build in DOWNLOAD mode. This should NOT get cache hits for affected nodes.
    addOptions(
        "--experimental_remote_analysis_cache_mode=download",
        "--experimental_skycache_analysis_only=true"
    );
    buildTarget("//pkg:hello");

    // Verify we got fewer/no cache hits (or at least we can verify it re-evaluated)
    // Wait, if pkg/BUILD is dirty, we might still get cache hits for UNRELATED nodes if there are any.
    // But //pkg:hello ConfiguredTarget depends on pkg/BUILD, so it must be evaluated.
    // Actually, in this simple build, almost everything depends on pkg/BUILD.
    // Let's assert that cache hits are 0 (or at least less than the hit run).
    RemoteAnalysisCachingServicesSupplier supplier =
        getRuntime().getWorkspace().remoteAnalysisCachingServicesSupplier();
    LocalAnalysisCacheClient client =
        (LocalAnalysisCacheClient) supplier.getAnalysisCacheClient().get();
    
    System.out.println("DEBUG Skycache: Cache hits in invalidation test: " + client.getCacheHits());
    // The top-level target //pkg:hello is invalidated because pkg/BUILD is dirty.
    // However, its dependency @bazel_tools//tools/genrule:genrule-setup.sh is NOT dirty, so it should still hit.
    assertThat(client.getCacheHits()).isEqualTo(1);
  }

  @Test
  public void testDiskCacheReader() throws Exception {
    injectVersionGetterForTesting(DUMMY_VERSION_GETTER);

    // 1. Build in UPLOAD mode (HDD)
    addOptions(
        "--experimental_remote_analysis_cache_mode=upload",
        "--experimental_remote_analysis_cache_storage=HDD",
        "--experimental_skycache_analysis_only=true"
    );
    write("pkg/BUILD",
        "genrule(",
        "  name = 'hello',",
        "  outs = ['hello.out'],",
        "  cmd = 'echo \"Hello World\" > $@',",
        ")");
    buildTarget("//pkg:hello");

    Path cacheDir = directories.getOutputBase().getRelative("skycache");
    assertThat(cacheDir.exists()).isTrue();

    // 2. Clean to reset in-memory graph.
    // The disk cache is under outputBase/skycache, which is NOT deleted by clean().
    clean();

    System.out.println("DEBUG Skycache: Cache dir exists after clean: " + cacheDir.exists());
    assertThat(cacheDir.exists()).isTrue();

    // 3. Build in DOWNLOAD mode (HDD)
    addOptions(
        "--experimental_remote_analysis_cache_mode=download",
        "--experimental_remote_analysis_cache_storage=HDD",
        "--experimental_skycache_analysis_only=true"
    );
    buildTarget("//pkg:hello");

    // Verify we got cache hits
    RemoteAnalysisCachingServicesSupplier supplier =
        getRuntime().getWorkspace().remoteAnalysisCachingServicesSupplier();
    LocalAnalysisCacheClient client =
        (LocalAnalysisCacheClient) supplier.getAnalysisCacheClient().get();

    System.out.println("DEBUG Skycache: Disk cache hits in test: " + client.getCacheHits());
    assertThat(client.getCacheHits()).isGreaterThan(0);
  }

  private static class MockLongVersionGetter implements LongVersionGetter {
    private final LongVersionGetter delegate;
    private final java.util.Map<Path, Long> fileOverrides = new java.util.HashMap<>();
    private final java.util.Map<Path, Long> listingOverrides = new java.util.HashMap<>();

    public MockLongVersionGetter(LongVersionGetter delegate) {
      this.delegate = delegate;
    }

    public void setFileOverride(Path path, long version) {
      fileOverrides.put(path, version);
    }

    public void setListingOverride(Path path, long version) {
      listingOverrides.put(path, version);
    }

    @Override
    public long getFilePathOrSymlinkVersion(Path path) throws java.io.IOException {
      if (fileOverrides.containsKey(path)) {
        return fileOverrides.get(path);
      }
      return delegate.getFilePathOrSymlinkVersion(path);
    }

    @Override
    public long getDirectoryListingVersion(Path path) throws java.io.IOException {
      if (listingOverrides.containsKey(path)) {
        return listingOverrides.get(path);
      }
      return delegate.getDirectoryListingVersion(path);
    }

    @Override
    public long getNonexistentPathVersion(Path path) throws java.io.IOException {
      return delegate.getNonexistentPathVersion(path);
    }
  }
}
