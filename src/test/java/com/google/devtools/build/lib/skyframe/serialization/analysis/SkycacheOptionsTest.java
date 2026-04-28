package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsParser;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SkycacheOptionsTest {

  @Test
  public void testDefaultValue() throws Exception {
    SkycacheOptions options = Options.getDefaults(SkycacheOptions.class);
    assertThat(options.getSkycache()).isEmpty();
  }

  @Test
  public void testSingleValue() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(SkycacheOptions.class).build();
    parser.parse("--experimental_skycache=read");
    SkycacheOptions options = parser.getOptions(SkycacheOptions.class);
    assertThat(options.getSkycache()).containsExactly("read");
  }

  @Test
  public void testMultiValue() throws Exception {
    OptionsParser parser = OptionsParser.builder().optionsClasses(SkycacheOptions.class).build();
    parser.parse("--experimental_skycache=read,write");
    SkycacheOptions options = parser.getOptions(SkycacheOptions.class);
    assertThat(options.getSkycache()).containsExactly("read", "write").inOrder();
  }
}
