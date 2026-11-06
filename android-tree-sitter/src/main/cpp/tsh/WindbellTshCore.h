/*
 * Copyright © 2026 com.zerostudio.idea.treesitter
 * Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
 * 纯声明式多主题方案与双管道高亮核心引擎
 */

#pragma once

#include <jni.h>
#include <cstdint>
#include <string>
#include <vector>
#include <memory>
#include <unordered_map>
#include <mutex>
#include <utility>

#include "tree_sitter/api.h"
#include "oniguruma.h"

namespace windbell::tsh {

#pragma pack(push, 4)
struct HighlightSpan {
    uint32_t start_byte;
    uint32_t end_byte;
    uint16_t style_id;
    uint16_t flags;
    int32_t priority = 0;
    uint32_t declaration_order = 0;
    uint8_t allow_overlap = 0;
    uint8_t reserved[3] = {};
};
#pragma pack(pop)

static_assert(sizeof(HighlightSpan) == 24, "HighlightSpan ABI must remain 24 bytes.");

enum class RegexRuleType : uint8_t {
    MATCH,
    BLOCK
};

struct CompiledRegexRule {
    RegexRuleType type = RegexRuleType::MATCH;
    uint16_t style_id = 0;
    bool has_style = false;
    uint16_t content_style_id = 0;
    bool has_content_style = false;
    regex_t* onig_start = nullptr;
    regex_t* onig_end = nullptr;
    std::unordered_map<int, uint16_t> group_style_map;
    std::unordered_map<std::string, uint16_t> named_group_style_map;
    std::unordered_map<int, uint16_t> start_group_style_map;
    std::unordered_map<std::string, uint16_t> start_named_group_style_map;
    std::unordered_map<int, uint16_t> end_group_style_map;
    std::unordered_map<std::string, uint16_t> end_named_group_style_map;
    std::vector<CompiledRegexRule> nested_rules;
    std::string id;
    int32_t priority = 0;
    uint32_t declaration_order = 0;
    bool enabled = true;
    bool allow_overlap = false;
    uint32_t regex_options = 0;
};

struct MonarchCase {
    regex_t* matcher = nullptr;
    uint16_t style_id = 0;
    std::string token_name;
    bool is_default = false;
};

struct MonarchRule {
    regex_t* pattern = nullptr;
    uint16_t style_id = 0;
    std::string token_name;
    bool has_style = false;
    std::string include_state;
    std::string next_state;
    std::string push_state;
    std::string switch_state;
    std::vector<MonarchCase> cases;
    bool pop = false;
    bool rematch = false;
    bool enabled = true;
    int32_t priority = 0;
    uint32_t declaration_order = 0;
};

// One independently compiled native Tree-sitter query block. The source text is
// consumed directly by ts_query_new; no generated SCM artifact exists.
struct NativeQueryProgram {
    struct PatternMetadata {
        uint32_t pattern_index = 0;
        int32_t priority = 0;
        uint32_t declaration_order = 0;
        bool has_priority = false;
        bool enabled = true;
    };

    std::string id;
    TSQuery* query = nullptr;
    int32_t priority = 0;
    uint32_t declaration_order = 0;
    bool enabled = true;
    std::unordered_map<uint32_t, PatternMetadata> pattern_metadata;
    // Capture names are query-local identifiers; bindings resolve them to
    // independent style IDs during compilation.
    std::unordered_map<std::string, std::string> capture_bindings;
    std::unordered_map<uint32_t, uint16_t> capture_style_ids;
    struct PredicateRegex { uint32_t pattern_index = 0; uint32_t capture_id = 0; regex_t* regex = nullptr; bool negate = false; };
    std::vector<PredicateRegex> predicate_regexes;
};

struct AstSelectorRule {
    TSSymbol target_symbol = 0;
    TSFieldId field_id = 0;
    TSSymbol parent_symbol = 0;
    TSSymbol ancestor_symbol = 0;
    TSSymbol child_symbol = 0;
    TSSymbol previous_sibling_symbol = 0;
    TSSymbol next_sibling_symbol = 0;
    std::string text_equals;
    std::string text_prefix;
    std::string text_suffix;
    bool has_text_equals = false;
    bool has_text_prefix = false;
    bool has_text_suffix = false;
    regex_t* text_regex = nullptr;
    std::string text_regex_pattern;
    uint32_t child_index = UINT32_MAX;
    uint32_t named_child_index = UINT32_MAX;
    int8_t first_child = -1;
    int8_t last_child = -1;
    // -1 means unconstrained; 0/1 require the corresponding Tree-sitter node flag.
    int8_t named = -1;
    int8_t missing = -1;
    int8_t extra = -1;
    int8_t error = -1;
    int8_t has_error = -1;
    uint16_t style_id = 0;
    bool has_style = false;
    int32_t priority = 0;
    uint32_t declaration_order = 0;
    std::string id;
    bool enabled = true;
    bool allow_overlap = false;
};

// Compiled style catalog entry. IDs are opaque runtime indices; names carry semantics.
struct TshStyle {
    std::string name;
    uint16_t style_id = 0;
    std::string scope;
    std::vector<std::string> tags;
    std::string description;
    std::string parent;
    bool deprecated = false;
    std::unordered_map<std::string, std::string> metadata;
};

// 单个 Token 的方案配色定义
struct SchemeColor {
    // Zero is an absent value, never a fallback color. The DSL owns all visible values.
    uint32_t light_color = 0;
    uint32_t dark_color = 0;
    uint16_t light_font_flags = 0;
    uint16_t dark_font_flags = 0;
    bool has_light_color = false;
    bool has_dark_color = false;
    bool explicit_light_color = false;
    bool explicit_dark_color = false;
};

// 多主题方案实体 (Schemes Block)
struct TshScopeColorRule {
    std::string selector;
    uint32_t light_color = 0;
    uint32_t dark_color = 0;
    bool has_light_color = false;
    bool has_dark_color = false;
    uint32_t declaration_order = 0;
};

struct ColorScheme {
    std::string name;
    std::string extends;
    std::vector<TshScopeColorRule> scope_rules;
    // Style ID -> Color mapping
    std::unordered_map<uint16_t, SchemeColor> colors;
    std::unordered_map<std::string, uint32_t> ui_colors;
    std::unordered_map<std::string, uint32_t> ui_light_colors;
    std::unordered_map<std::string, uint32_t> ui_dark_colors;
};

struct TshAutoClosingPair {
    std::string open;
    std::string close;
    std::vector<std::string> not_in;
};

struct TshOnEnterRule {
    std::string before_text;
    std::string after_text;
    std::string previous_line_text;
    std::string indent_action;
    std::string append_text;
    uint32_t remove_text = 0;
};

struct TshEditorMetadata {
    std::vector<std::string> line_comments;
    std::vector<std::pair<std::string, std::string>> block_comments;
    std::vector<std::pair<std::string, std::string>> brackets;
    std::vector<std::string> bracket_not_in;
    std::vector<TshAutoClosingPair> auto_closing_pairs;
    std::vector<std::pair<std::string, std::string>> surrounding_pairs;
    std::string word_pattern;
    std::string increase_indent_pattern;
    std::string decrease_indent_pattern;
    std::string indent_next_line_pattern;
    std::string unindented_line_pattern;
    std::vector<std::string> completion_trigger_characters;
    std::vector<std::string> electric_characters;
    std::vector<TshOnEnterRule> on_enter_rules;
    std::string auto_close_before_pattern;
    std::unordered_map<std::string, std::string> options;
};

class TshGrammar {
public:
    std::string id;
    std::string name;
    std::vector<std::string> file_extensions;
    std::vector<std::string> aliases;
    std::vector<std::string> mime_types;
    std::string first_line_pattern;
    regex_t* first_line_regex = nullptr;
    const TSLanguage* ts_language = nullptr;
    
    std::unordered_map<std::string, uint16_t> style_name_to_id;
    std::unordered_map<std::string, TshStyle> style_definitions;
    std::vector<TshStyle> styles;
    
    // 多主题方案集合 (Key: scheme 名称，如 "darcula", "github_light")
    std::unordered_map<std::string, ColorScheme> schemes;
    std::string default_scheme_name;
    bool default_is_dark = false;
    TshEditorMetadata editor_metadata;
    uint32_t default_regex_options = ONIG_OPTION_DEFAULT;

    std::vector<CompiledRegexRule> regex_rules;
    std::unordered_map<std::string, std::vector<MonarchRule>> monarch_states;
    std::unordered_map<std::string, std::string> monarch_variables;
    std::string monarch_token_postfix;
    std::string monarch_start_state = "root";
    uint16_t monarch_default_style_id = 0;
    std::string monarch_default_style_name;
    bool monarch_has_default_style = false;
    std::vector<AstSelectorRule> ast_selector_rules;
    std::vector<NativeQueryProgram> native_queries;

    ~TshGrammar();
};

class WindbellEngine {
public:
    static WindbellEngine& getInstance() {
        static WindbellEngine instance;
        return instance;
    }

    bool registerGrammar(const std::string& extension, std::shared_ptr<TshGrammar> grammar);
    std::shared_ptr<const TshGrammar> getGrammarByExtension(const std::string& extension);

    // 双通道混合高亮入口（线程安全）
    std::vector<HighlightSpan> highlightHybrid(
        const TSLanguage* language,
        TSTree* tree,
        const uint16_t* input_chars,
        uint32_t length_chars,
        const std::string& extension
    );

private:
    WindbellEngine();
    ~WindbellEngine();

    void matchBlockRecursive(
        const CompiledRegexRule& rule,
        const UChar* text_start,
        const UChar* text_end,
        const UChar* range_start,
        const UChar* range_end,
        std::vector<HighlightSpan>& out_spans
    );

    std::mutex registry_lock_;
    // Published grammars are immutable snapshots. Compilation owns the mutable builder;
    // readers only receive const access for the entire render lifetime.
    std::unordered_map<std::string, std::shared_ptr<const TshGrammar>> grammar_registry_;
};

} // namespace windbell::tsh