package com.zerostudio.idea.treesitter;

import com.android.build.gradle.BasePlugin;
import com.android.build.gradle.internal.plugins.AppPlugin;
import com.android.build.gradle.internal.plugins.BasePluginAccessor;
import com.android.build.gradle.internal.plugins.LibraryPlugin;
import org.gradle.api.plugins.PluginManager;

/**
 * Keeps AGP's deeply parameterized BasePlugin type out of Kotlin's Java
 * signature enhancement. Kotlin 2.4.x can crash while enhancing the
 * AppPlugin/LibraryPlugin inheritance bounds in this AGP internal API.
 */
final class AgpPluginServicesBridge {
  private AgpPluginServicesBridge() {}

  @SuppressWarnings({"rawtypes", "unchecked"})
  static Object getDslServices(PluginManager plugins) {
    final BasePlugin plugin;
    if (plugins.hasPlugin("com.android.application")) {
      plugin = (BasePlugin) plugins.getPlugin(AppPlugin.class);
    } else if (plugins.hasPlugin("com.android.library")) {
      plugin = (BasePlugin) plugins.getPlugin(LibraryPlugin.class);
    } else {
      throw new IllegalStateException(
          "The TreeSitter plugin requires com.android.application or com.android.library");
    }
    return BasePluginAccessor.getDslServices(plugin);
  }
}
