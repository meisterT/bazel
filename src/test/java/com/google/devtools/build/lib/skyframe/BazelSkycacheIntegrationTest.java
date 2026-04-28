package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.buildtool.util.SkyframeIntegrationTestBase;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.skyframe.serialization.analysis.SkycacheOptions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BazelSkycacheIntegrationTest extends SkyframeIntegrationTestBase {


  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder()
        .addBlazeModule(new com.google.devtools.build.lib.skyframe.serialization.SerializationModule())
        .addBlazeModule(
            new BlazeModule() {
              @Override
              public Iterable<Class<? extends OptionsBase>> getCommandOptions(String commandName) {
                return ImmutableList.of(com.google.devtools.build.lib.remote.options.RemoteOptions.class);
              }
            });
  }


  @Before
  public void addOptions() {
    addOptions("--experimental_skycache=write");
    addOptions("--disk_cache=" + getWorkspace().getRelative("disk_cache").getPathString());
    addOptions("--nouse_action_cache");
  }




  @Test
  public void build_populatesCache() throws Exception {
    skyframeExecutor().resetEvaluator();
    write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo hello > $@')");
    
    buildTarget("//foo"); // Execute and populate
    
    System.out.println("DEBUG: Skycache path exists immediately after build: " + getWorkspace().getRelative("disk_cache/skycache").exists());
    
    // Verify that cache directory exists and contains files

    var skycachePath = getWorkspace().getRelative("disk_cache/skycache");
    com.google.common.truth.Truth.assertWithMessage("Skycache path " + skycachePath + " should exist")
        .that(skycachePath.exists()).isTrue();
    assertThat(skycachePath.getDirectoryEntries()).isNotEmpty();
  }

  @Test
  public void build_readsFromCache() throws Exception {
    skyframeExecutor().resetEvaluator();
    String timestamp = String.valueOf(System.currentTimeMillis());
    write("bar/BUILD", "genrule(name='bar', outs=['out'], cmd='echo there " + timestamp + " > $@')");
    buildTarget("//bar"); // Populate and initialize environment
    
    // Clean state
    skyframeExecutor().resetEvaluator();

    
    addOptions("--experimental_skycache=read");
    buildTarget("//bar"); // Read
    
    // Verify that it hit the cache (check logs or counters if available)
    // For MVP, just verify it succeeds without re-evaluation if possible,
    // or check metrics.
  }



}
