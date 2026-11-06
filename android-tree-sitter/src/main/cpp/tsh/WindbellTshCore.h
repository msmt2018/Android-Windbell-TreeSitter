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

#include "tree_sitter/api.h"
#include "oniguruma.h"

namespace windbell::tsh {

#pragma pack(push, 4)
struct HighlightSpan {
    uint32_t start_byte; // UTF-16 字节偏移量
    uint32_t end_byte;   // UTF-16 字节偏移量
    uint16_t style_id;   // 方案样式索引
    uint16_t flags;      // 字体修饰位: 0x01(粗体), 0x02(斜体), 0x04(下划线)
};
#pragma pack(pop)

static_assert(sizeof(HighlightSpan) == 12, "HighlightSpan must be exactly 12 bytes.");

enum class RegexRuleType : uint8_t {
    MATCH,
    BLOCK
};

struct CompiledRegexRule {
    RegexRuleType type = RegexRuleType::MATCH;
    uint16_t style_id = 0;
    regex_t* onig_start = nullptr;
    regex_t* onig_end = nullptr;
    std::unordered_map<int, uint16_t> group_style_map;
    std::vector<CompiledRegexRule> nested_rules;
};

struct AstSelectorRule {
    TSSymbol target_symbol = 0;
    TSFieldId field_id = 0;
    TSSymbol parent_symbol = 0;
    uint16_t style_id = 0;
    bool has_style = false;
};

// Compiled style catalog entry. IDs are opaque runtime indices; names carry semantics.
struct TshStyle {
    std::string name;
    uint16_t style_id = 0;
    uint32_t light_color = 0;
    uint32_t dark_color = 0;
    uint16_t font_flags = 0;
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
    uint16_t font_flags = 0;
    bool has_light_color = false;
    bool has_dark_color = false;
};

// 多主题方案实体 (Schemes Block)
struct ColorScheme {
    std::string name;
    // Style ID -> Color mapping
    std::unordered_map<uint16_t, SchemeColor> colors;
};

class TshGrammar {
public:
    std::string name;
    std::vector<std::string> file_extensions;
    const TSLanguage* ts_language = nullptr;
    
    std::unordered_map<std::string, uint16_t> style_name_to_id;
    std::unordered_map<std::string, TshStyle> style_definitions;
    std::vector<TshStyle> styles;
    
    // 多主题方案集合 (Key: scheme 名称，如 "darcula", "github_light")
    std::unordered_map<std::string, ColorScheme> schemes;
    std::string default_scheme_name;

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