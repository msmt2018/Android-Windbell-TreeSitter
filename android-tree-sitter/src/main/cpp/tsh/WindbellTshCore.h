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
    regex_t* onig_start = nullptr;
    regex_t* onig_end = nullptr;
    std::unordered_map<int, uint16_t> group_style_map;
    std::vector<CompiledRegexRule> nested_rules;
    std::string id;
    int32_t priority = 0;
    uint32_t declaration_order = 0;
    bool enabled = true;
    bool allow_overlap = false;
};

struct AstSelectorRule {
    TSSymbol target_symbol = 0;
    TSFieldId field_id = 0;
    TSSymbol parent_symbol = 0;
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
};

// 多主题方案实体 (Schemes Block)
struct ColorScheme {
    std::string name;
    std::string extends;
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
};

class TshGrammar {
public:
    std::string id;
    std::string name;
    std::vector<std::string> file_extensions;
    std::vector<std::string> aliases;
    std::vector<std::string> mime_types;
    std::string first_line_pattern;
    const TSLanguage* ts_language = nullptr;
    
    std::unordered_map<std::string, uint16_t> style_name_to_id;
    std::unordered_map<std::string, TshStyle> style_definitions;
    std::vector<TshStyle> styles;
    
    // 多主题方案集合 (Key: scheme 名称，如 "darcula", "github_light")
    std::unordered_map<std::string, ColorScheme> schemes;
    std::string default_scheme_name;
    bool default_is_dark = false;
    TshEditorMetadata editor_metadata;

    std::vector<CompiledRegexRule> regex_rules;
    std::vector<AstSelectorRule> ast_selector_rules;
    TSQuery* dynamic_ts_query = nullptr;

    ~TshGrammar();
};

class WindbellEngine {
public:
    static WindbellEngine& getInstance() {
        static WindbellEngine instance;
        return instance;
    }

    bool registerGrammar(const std::string& extension, std::shared_ptr<TshGrammar> grammar);
    std::shared_ptr<TshGrammar> getGrammarByExtension(const std::string& extension);

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
    std::unordered_map<std::string, std::shared_ptr<TshGrammar>> grammar_registry_;
};

} // namespace windbell::tsh