/*
 * Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
 *
 * Copyright (C) 2026 com.zerostudio.idea.treesitter
 *
 * 核心架构定义：
 * 1. 纯声明式语法对象模型（完全由 .tsh 动态生成，零硬编码）。
 * 2. 宏观 Tree-sitter AST 选择器 + 微观 Oniguruma 正则双引擎契约。
 * 3. 内存布局严格适配 ARM64 64 字节 Cache Line。
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

// 紧凑高亮 Span：12 字节紧凑对齐，4 个 Span 严格填满 1 个 64 字节 Cache Line
#pragma pack(push, 4)
struct HighlightSpan {
    uint32_t start_byte; // UTF-16 字节偏移量 (必须为偶数)
    uint32_t end_byte;   // UTF-16 字节偏移量
    uint16_t style_id;   // 调色板样式索引
    uint16_t flags;      // 字体修饰位: 0x01(粗体), 0x02(斜体), 0x04(下划线)
};
#pragma pack(pop)

static_assert(sizeof(HighlightSpan) == 12, "HighlightSpan must be exactly 12 bytes.");

enum class RegexRuleType : uint8_t {
    MATCH, // 单行/单次正则匹配
    BLOCK  // 跨行/嵌套块匹配 (Start/End)
};

// 正则微观规则
struct CompiledRegexRule {
    RegexRuleType type = RegexRuleType::MATCH;
    uint16_t style_id = 0;
    
    regex_t* onig_start = nullptr;
    regex_t* onig_end = nullptr;

    std::unordered_map<int, uint16_t> group_style_map; // 捕获组样式细分
    std::vector<CompiledRegexRule> nested_rules;       // 块内内嵌规则
};

// AST 宏观原生选择器规则（从 .tsh 中的 ast: [...] 动态加载，不含任何硬编码）
struct AstSelectorRule {
    TSSymbol target_symbol = 0; // 目标节点符号 ID (0 代表通用/根据字段匹配)
    TSFieldId field_id = 0;     // 字段槽位 ID (如 "name", "field")
    TSSymbol parent_symbol = 0; // 限制必须处于该父节点内部 (0 代表任意)
    
    uint16_t style_id = 0;      // 绑定的高亮样式 ID
    uint16_t outline_kind = 0;  // 1: Class, 2: Method, 3: Field, 4: Enum, 0: 无
    bool is_foldable = false;   // 是否生成代码折叠
};

// 调色板多态样式定义
struct StyleDefinition {
    uint16_t style_id = 0;
    std::string name;
    uint32_t light_color = 0xFF000000; // 0xAARRGGBB
    uint32_t dark_color  = 0xFFFFFFFF; // 0xAARRGGBB
    uint16_t font_flags  = 0;          // 字体 flags
};

// 折叠范围数据载体
struct FoldRange {
    uint32_t start_byte;
    uint32_t end_byte;
    uint8_t kind; // 0: 代码块, 1: 注释, 2: 依赖/导入段
};

// 符号大纲数据载体
struct OutlineTag {
    uint32_t start_byte;
    uint32_t end_byte;
    char name[64];
    uint16_t kind;
};

// 悬停感知信息
struct HoverInfo {
    uint32_t start_byte;
    uint32_t end_byte;
    std::string node_type;
    std::string detail;
};

// 语法完整定义包 (由 TshCompiler 动态编译生成)
class TshGrammar {
public:
    std::string name;
    std::vector<std::string> file_extensions;
    
    std::unordered_map<std::string, uint16_t> style_name_to_id;
    std::vector<StyleDefinition> styles;

    // 正则微观匹配链
    std::vector<CompiledRegexRule> regex_rules;

    // 原生 AST 选择器规则链（纯数值符号匹配，吞吐量极致）
    std::vector<AstSelectorRule> ast_selector_rules;

    // 动态编译的外部 SCM TSQuery (若 .tsh 中定义了动态 query)
    TSQuery* dynamic_ts_query = nullptr;

    ~TshGrammar();
};

// 全局高亮引擎单例
class WindbellEngine {
public:
    static WindbellEngine& getInstance() {
        static WindbellEngine instance;
        return instance;
    }

    bool registerGrammar(const std::string& extension, std::shared_ptr<TshGrammar> grammar);
    std::shared_ptr<TshGrammar> getGrammarByExtension(const std::string& extension);

    // 双通道混合高亮渲染入口
    std::vector<HighlightSpan> highlightHybrid(
        const TSLanguage* language,
        TSTree* tree,
        const uint16_t* input_chars,
        uint32_t length_chars,
        const std::string& extension
    );

    std::vector<FoldRange> computeFoldRanges(TSTree* tree, const uint16_t* input_chars, uint32_t length_chars, const std::string& extension);
    std::vector<OutlineTag> computeOutline(TSTree* tree, const uint16_t* input_chars, uint32_t length_chars, const std::string& extension);
    bool getHoverInfo(TSTree* tree, uint32_t byte_offset, HoverInfo& out_info);

private:
    WindbellEngine();
    ~WindbellEngine();
    WindbellEngine(const WindbellEngine&) = delete;
    WindbellEngine& operator=(const WindbellEngine&) = delete;

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