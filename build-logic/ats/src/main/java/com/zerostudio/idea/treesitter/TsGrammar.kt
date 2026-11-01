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

package com.zerostudio.idea.treesitter

import com.google.gson.annotations.SerializedName

/**
 * Model for grammar entries in `grammars.json` file.
 *
 * This data class maps directly to the configuration items used by the dynamic module plugin
 * and grammar task generation pipelines to build individual or composite Tree-Sitter language modules.
 *
 * @author Akash Yadav
 * @author android_zero <github.com/msmt2018> [update] : Enhanced with comprehensive JSON property specifications for single and composite multi-parser architectures.
 */
data class TsGrammar(

    /**
     * The unique identifier / name of the grammar module (e.g., "yaml", "markdown", "ocaml").
     *
     * - **Usage**: Used as the primary key to name the Gradle module (`tree-sitter-<name>`),
     *   target shared library (`libtree-sitter-<name>.so`), and route Java package directories.
     */
    @SerializedName("name")
    val name: String,

    /**
     * Flag indicating whether this grammar repository is a composite multi-parser architecture.
     *
     * - **Boolean Specification**:
     *   - `true`: This repository contains multiple sub-parsers under deep paths (e.g., Markdown with
     *     `tree-sitter-markdown` and `tree-sitter-markdown-inline`, or OCaml with multiple syntax interfaces).
     *     The build pipeline will compile all corresponding `parser.c` files into a single optimized `.so` binary.
     *   - `false` (or omitted): This is a traditional single-parser repository governed by a single
     *     root or standard `src/parser.c` entry.
     */
    @SerializedName("composite")
    val composite: Boolean = false,

    /**
     * The precise C language entry function symbol extracted from `parser.c` for single parsers.
     *
     * - **Usage**: Only active when [composite] is `false`.
     * - **Purpose**: Avoids hardcoding or unreliable string guessing (like `tree_sitter_$name`).
     *   It stores the exact C symbol signature (e.g., `tree_sitter_yaml`) parsed via automated AST inspection,
     *   ensuring 100% precision during JNI dynamic method registration (`JNINativeMethod`).
     */
    @SerializedName("entry")
    val entry: String? = null,

    /**
     * List of relative sub-directory paths containing individual sub-parsers.
     *
     * - **Usage**: Only active and required when [composite] is `true`.
     * - **Purpose**: Directs the Gradle task and CMake builder to locate all nested parser source sets
     *   (e.g., `["tree-sitter-markdown", "tree-sitter-markdown-inline"]` or `["ocaml/grammars/interface", "ocaml/grammars/ocaml"]`),
     *   automatically collecting their respective `src/parser.c` and scanner implementations.
     */
    @SerializedName("sub.paths")
    val subPaths: List<String> = emptyList(),

    /**
     * List of precise C language entry function symbols corresponding one-to-one with [subPaths].
     *
     * - **Usage**: Only active and required when [composite] is `true`.
     * - **Purpose**: Provides a parallel array of exact C entry symbols (e.g., `["tree_sitter_markdown", "tree_sitter_markdown_inline"]`)
     *   extracted from each sub-parser's `parser.c`. This ensures the JNI wrapper can correctly bind multiple
     *   independent `TSLanguage*` instances within a single shared `.so` library without any naming conflicts.
     */
    @SerializedName("sub.entries")
    val subEntries: List<String> = emptyList(),

    /**
     * List of extra source or scanner files (e.g., `src/scanner.c`, `scanner.cc`) required by the grammar.
     *
     * - **Usage**: Applicable to both single and composite configurations.
     * - **Purpose**: Tells the build logic which extra C/C++ source files need to be explicitly compiled
     *   alongside the main `parser.c` state machine to support custom external lexers.
     */
    @SerializedName("src.extra")
    val srcExtra: List<String> = emptyList()
) {

    /**
     * Returns the capitalized grammar name (e.g., "yaml" becomes "Yaml", "markdown" becomes "Markdown").
     *
     * - **Purpose**: Used heavily in code generation for naming Java class binders (e.g., `TSLanguageYaml`)
     *   and native wrapper helper methods.
     */
    fun capitalizedName() = name.replaceFirstChar { it.titlecaseChar() }
}