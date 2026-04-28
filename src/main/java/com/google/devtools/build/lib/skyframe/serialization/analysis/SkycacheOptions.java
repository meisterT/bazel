package com.google.devtools.build.lib.skyframe.serialization.analysis;

import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsClass;
import java.util.List;

/** Options for the Skycache feature (analysis caching). */
@OptionsClass
public abstract class SkycacheOptions extends OptionsBase {

  @Option(
      name = "experimental_skycache",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      converter = Converters.CommaSeparatedOptionListConverter.class,
      help =
          "Controls access to the Skycache analysis cache. "
              + "Accepts comma-separated values like 'read', 'write'. "
              + "Empty string means disabled.")
  public abstract List<String> getSkycache();

  public abstract void setSkycache(List<String> value);


}
