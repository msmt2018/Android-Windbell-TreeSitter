/*
 *  This file is part of android-tree-sitter.
 *
 *  android-tree-sitter library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  android-tree-sitter library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *  along with android-tree-sitter.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.zerostudio.idea.treesitter;

import com.zerostudio.idea.treesitter.util.Pair;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides cached instances of {@link TSLanguage}.
 *
 * @author Akash Yadav
 */
public final class TSLanguageCache {

  private static final Map<String, TSLanguage> languagesByName = new ConcurrentHashMap<>();
  // External grammars are identified by both logical name and canonical .so path.
  // A name-only cache aliases different binaries that export the same entry symbol.
  private static final Map<String, TSLanguage> languagesByPath = new ConcurrentHashMap<>();
  private static final Map<Long, TSLanguage> languagesByPtr = new ConcurrentHashMap<>();

  private static String pathKey(String name, String path) {
    return name + "\\u0000" + path;
  }

  private TSLanguageCache() {
  }

  /**
   * Caches the given {@link TSLanguage}.
   *
   * @param name     The name of the language. This can be later used to retrieve the language
   *                 instance using {@link TSLanguageCache#get(String)}.
   * @param language The {@link TSLanguage} instance to cache.
   */
  public static void cache(String name, TSLanguage language) {
    languagesByName.putIfAbsent(name, language);
    languagesByPtr.put(language.getNativeObject(), language);
  }

  public static void cache(String name, String libraryPath, TSLanguage language) {
    languagesByPath.put(pathKey(name, libraryPath), language);
    languagesByName.putIfAbsent(name, language);
    languagesByPtr.put(language.getNativeObject(), language);
  }

  public static TSLanguage get(String name, String libraryPath) {
    if (name == null || libraryPath == null) return null;
    return languagesByPath.get(pathKey(name, libraryPath));
  }

  /**
   * Get the {@link TSLanguage} instance by the given pointer.
   *
   * @param name The name of the language to get the {@link TSLanguage} instance for.
   * @return The {@link TSLanguage} instance for the given language, or <code>null</code> if the
   * language was not cached.
   */
  public static TSLanguage get(String name) {
    if (name == null) {
      return null;
    }
    return languagesByName.get(name);
  }

  /**
   * Get the {@link TSLanguage} instance by the given pointer.
   *
   * @param pointer The pointer to the native language instance.
   * @return The {@link TSLanguage} instance for the given pointer, or <code>null</code> if the
   * language was not cached.
   */
  public static TSLanguage get(long pointer) {
    if (pointer == 0) {
      return null;
    }
    return languagesByPtr.get(pointer);
  }

  /**
   * Calls {@link TSLanguage#close()} on each cached language that is externally loaded. This makes
   * sure that any language that may have been opened with
   * {@link TSLanguage#loadLanguage(String, String)} closes the associated native library handle.
   */
  public static void closeExternal() {
    final var toRemove = new HashSet<Pair<String, Long>>();
    languagesByName.forEach((name, lang) -> {
      if (lang.isExternal()) {
        toRemove.add(Pair.of(name, lang.getNativeObject()));
        lang.close();
      }
    });

    for (Pair<String, Long> lang : toRemove) {
      //noinspection resource
      TSLanguage removed = languagesByPtr.remove(lang.second);
      if (removed != null) {
        languagesByName.values().removeIf(value -> value == removed);
        languagesByPath.values().removeIf(value -> value == removed);
      }
    }
  }

  /**
   * Remove the given language from the cache.
   *
   * @param language The language to remove.
   */
  static void remove(TSLanguage language) {
    if (language == null) {
      return;
    }

    // Remove only entries that still point at this exact object. Another .so
    // may have been loaded under the same logical grammar name in the meantime.
    languagesByName.entrySet().removeIf(entry -> entry.getValue() == language);
    languagesByPath.entrySet().removeIf(entry -> entry.getValue() == language);
    languagesByPtr.entrySet().removeIf(entry -> entry.getValue() == language);
  }
}