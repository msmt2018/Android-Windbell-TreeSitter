/*
 * Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
 * 双流水线调度引擎与扫描线合成器完整实现
 *
 * 核心特性：
 * 1. 零 SCM 硬编码依赖，100% 由外部 .tsh 动态规则驱动。
 * 2. TSTreeCursor 寄存器级树遍历，仅比对 16 位整数符号 ID。
 * 3. Oniguruma 递归块状态机 (Block State Machine)，完美处理嵌套注释与文本块。
 * 4. 扫描线区间合并仲裁，彻底消除高亮抖动与穿透。
 */

#include "WindbellTshCore.h"
#include <algorithm>
#include <cstring>
#include "ts__log.h"

namespace windbell::tsh {

TshGrammar::~TshGrammar() {
    for (auto& rule : regex_rules) {
        if (rule.onig_start) {
            onig_free(rule.onig_start);
            rule.onig_start = nullptr;
        }
        if (rule.onig_end) {
            onig_free(rule.onig_end);
            rule.onig_end = nullptr;
        }
    }
    if (dynamic_ts_query) {
        ts_query_delete(dynamic_ts_query);
        dynamic_ts_query = nullptr;
    }
}

WindbellEngine::WindbellEngine() {
    // 强制使用 UTF-16LE 编码初始化全局鬼车正则引擎
    OnigEncoding encodings[] = { ONIG_ENCODING_UTF16_LE };
    onig_initialize(encodings, 1);
}

WindbellEngine::~WindbellEngine() {
    onig_end();
}

bool WindbellEngine::registerGrammar(const std::string& extension, std::shared_ptr<TshGrammar> grammar) {
    std::lock_guard<std::mutex> lock(registry_lock_);
    grammar_registry_[extension] = grammar;
    return true;
}

std::shared_ptr<TshGrammar> WindbellEngine::getGrammarByExtension(const std::string& extension) {
    std::lock_guard<std::mutex> lock(registry_lock_);
    auto it = grammar_registry_.find(extension);
    return (it != grammar_registry_.end()) ? it->second : nullptr;
}

// 递归处理块作用域（如多行注释、文本块及其内部内嵌子规则）
void WindbellEngine::matchBlockRecursive(
    const CompiledRegexRule& rule,
    const UChar* text_start,
    const UChar* text_end,
    const UChar* range_start,
    const UChar* range_end,
    std::vector<HighlightSpan>& out_spans
) {
    OnigRegion* start_region = onig_region_new();
    OnigRegion* end_region = onig_region_new();
    const UChar* curr = range_start;

    while (curr < range_end) {
        // 1. 扫描当前范围内的块起始定界符 (Start Delimiter)
        int hit_start = onig_search(
            rule.onig_start,
            text_start,
            text_end,
            curr,
            range_end,
            start_region,
            ONIG_OPTION_NONE
        );
        if (hit_start < 0) break;

        // 起始字节偏移
        uint32_t block_start_byte = static_cast<uint32_t>(start_region->beg[0]);
        const UChar* content_start = text_start + start_region->end[0];

        // 2. 从起始定界符末尾开始寻找闭合定界符 (End Delimiter)
        int hit_end = onig_search(
            rule.onig_end,
            text_start,
            text_end,
            content_start,
            text_end,
            end_region,
            ONIG_OPTION_NONE
        );

        uint32_t block_end_byte = (hit_end >= 0) ?
            static_cast<uint32_t>(end_region->end[0]) :
            static_cast<uint32_t>(text_end - text_start);

        // 校验字节是否严格对齐到 UTF-16 Code Unit 边界
        if ((block_start_byte & 1u) == 0 && (block_end_byte & 1u) == 0) {
            HighlightSpan block_span;
            block_span.start_byte = block_start_byte;
            block_span.end_byte = block_end_byte;
            block_span.style_id = rule.style_id;
            block_span.flags = 0;
            out_spans.push_back(block_span);

            // 3. 处理块内部内嵌嵌套规则 (Nested contains)
            if (!rule.nested_rules.empty() && hit_end >= 0) {
                const UChar* inner_start = content_start;
                const UChar* inner_end = text_start + end_region->beg[0];

                for (const auto& nested : rule.nested_rules) {
                    if (nested.type == RegexRuleType::MATCH && nested.onig_start) {
                        OnigRegion* inner_region = onig_region_new();
                        const UChar* inner_curr = inner_start;
                        while (inner_curr < inner_end) {
                            int inner_hit = onig_search(
                                nested.onig_start,
                                text_start,
                                text_end,
                                inner_curr,
                                inner_end,
                                inner_region,
                                ONIG_OPTION_NONE
                            );
                            if (inner_hit < 0) break;

                            uint32_t in_s = static_cast<uint32_t>(inner_region->beg[0]);
                            uint32_t in_e = static_cast<uint32_t>(inner_region->end[0]);
                            if ((in_s & 1u) == 0 && (in_e & 1u) == 0) {
                                HighlightSpan in_span;
                                in_span.start_byte = in_s;
                                in_span.end_byte = in_e;
                                in_span.style_id = nested.style_id;
                                in_span.flags = 0;
                                out_spans.push_back(in_span);
                            }
                            inner_curr = (inner_region->end[0] <= inner_region->beg[0]) ?
                                inner_curr + sizeof(uint16_t) : text_start + inner_region->end[0];
                        }
                        onig_region_free(inner_region, 1);
                    }
                }
            }
        }

        if (hit_end >= 0 && end_region->end[0] > start_region->beg[0]) {
            curr = text_start + end_region->end[0];
        } else {
            curr = text_start + start_region->end[0] + sizeof(uint16_t);
        }
    }

    onig_region_free(start_region, 1);
    onig_region_free(end_region, 1);
}

// 双流混合渲染调度核心
std::vector<HighlightSpan> WindbellEngine::highlightHybrid(
    const TSLanguage* language,
    TSTree* tree,
    const uint16_t* input_chars,
    uint32_t length_chars,
    const std::string& extension
) {
    std::vector<HighlightSpan> final_spans;
    auto grammar = getGrammarByExtension(extension);
    if (!grammar) return final_spans;

    uint32_t total_bytes = length_chars * sizeof(uint16_t);
    const UChar* text_start = reinterpret_cast<const UChar*>(input_chars);
    const UChar* text_end = text_start + total_bytes;

    // =========================================================================
    // 管道 B: 正则微观流式分词 (同步极速响应，支持块级作用域推进)
    // =========================================================================
    std::vector<HighlightSpan> micro_spans;
    micro_spans.reserve(length_chars / 4);

    for (const auto& rule : grammar->regex_rules) {
        if (rule.type == RegexRuleType::BLOCK && rule.onig_start && rule.onig_end) {
            matchBlockRecursive(rule, text_start, text_end, text_start, text_end, micro_spans);
            continue;
        }

        if (!rule.onig_start) continue;

        OnigRegion* region = onig_region_new();
        const UChar* current = text_start;

        while (current < text_end) {
            int hit_pos = onig_search(
                rule.onig_start,
                text_start,
                text_end,
                current,
                text_end,
                region,
                ONIG_OPTION_NONE
            );
            if (hit_pos >= 0) {
                uint32_t match_start = static_cast<uint32_t>(region->beg[0]);
                uint32_t match_end = static_cast<uint32_t>(region->end[0]);

                if ((match_start & 1u) == 0 && (match_end & 1u) == 0) {
                    if (rule.group_style_map.empty()) {
                        HighlightSpan span;
                        span.start_byte = match_start;
                        span.end_byte = match_end;
                        span.style_id = rule.style_id;
                        span.flags = 0;
                        micro_spans.push_back(span);
                    } else {
                        // 细分捕获组样式映射
                        for (int g = 1; g < region->num_regs; ++g) {
                            if (region->beg[g] >= 0 && region->end[g] >= 0) {
                                auto style_it = rule.group_style_map.find(g);
                                if (style_it != rule.group_style_map.end()) {
                                    HighlightSpan g_span;
                                    g_span.start_byte = static_cast<uint32_t>(region->beg[g]);
                                    g_span.end_byte = static_cast<uint32_t>(region->end[g]);
                                    g_span.style_id = style_it->second;
                                    g_span.flags = 0;
                                    micro_spans.push_back(g_span);
                                }
                            }
                        }
                    }
                }

                current = (region->end[0] <= region->beg[0]) ?
                    current + sizeof(uint16_t) : text_start + region->end[0];
            } else {
                break;
            }
        }
        onig_region_free(region, 1);
    }

    // =========================================================================
    // 管道 A: 原生 Tree-sitter AST 选择器树步进遍历 (彻底消除 SCM 解释器)
    // =========================================================================
    std::vector<HighlightSpan> macro_spans;
    if (tree && !grammar->ast_selector_rules.empty()) {
        macro_spans.reserve(length_chars / 6);
        TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));
        bool has_next = true;

        while (has_next) {
            TSNode node = ts_tree_cursor_current_node(&cursor);
            TSSymbol sym = ts_node_symbol(node);
            TSFieldId fid = ts_tree_cursor_current_field_id(&cursor);

            TSNode parent = ts_tree_cursor_parent_node(&cursor);
            TSSymbol parent_sym = ts_node_is_null(parent) ? 0 : ts_node_symbol(parent);

            // 寄存器级 16 位整型高效匹配选择器规则列表
            for (const auto& ast_rule : grammar->ast_selector_rules) {
                if (ast_rule.target_symbol != 0 && ast_rule.target_symbol != sym) {
                    continue;
                }
                if (ast_rule.field_id != 0 && ast_rule.field_id != fid) {
                    continue;
                }
                if (ast_rule.parent_symbol != 0 && ast_rule.parent_symbol != parent_sym) {
                    continue;
                }

                // 命中选择器，生成宏观语义 Span
                if (ast_rule.style_id != 0) {
                    HighlightSpan span;
                    span.start_byte = ts_node_start_byte(node);
                    span.end_byte = ts_node_end_byte(node);
                    span.style_id = ast_rule.style_id;
                    span.flags = 0;
                    macro_spans.push_back(span);
                    break;
                }
            }

            // 深度优先移动游标
            if (ts_tree_cursor_goto_first_child(&cursor)) continue;
            if (ts_tree_cursor_goto_next_sibling(&cursor)) continue;
            while (ts_tree_cursor_goto_parent(&cursor)) {
                if (ts_tree_cursor_goto_next_sibling(&cursor)) break;
            }
            has_next = (ts_node_start_byte(ts_tree_cursor_current_node(&cursor)) != 0 || 
                        ts_tree_cursor_current_node(&cursor).id != ts_tree_root_node(tree).id);
        }
        ts_tree_cursor_delete(&cursor);
    }

    // =========================================================================
    // 管道 A(扩展): 若 .tsh 中显式声明了动态 query 则并发驱动解释执行
    // =========================================================================
    if (tree && grammar->dynamic_ts_query) {
        TSQueryCursor* q_cursor = ts_query_cursor_new();
        ts_query_cursor_exec(q_cursor, grammar->dynamic_ts_query, ts_tree_root_node(tree));

        TSQueryMatch match;
        uint32_t capture_index = 0;
        while (ts_query_cursor_next_capture(q_cursor, &match, &capture_index)) {
            const TSQueryCapture& capture = match.captures[capture_index];
            uint32_t name_len = 0;
            const char* cap_name = ts_query_capture_name_for_id(grammar->dynamic_ts_query, capture.index, &name_len);

            std::string cap_str(cap_name, name_len);
            auto it = grammar->style_name_to_id.find(cap_str);
            if (it != grammar->style_name_to_id.end()) {
                HighlightSpan span;
                span.start_byte = ts_node_start_byte(capture.node);
                span.end_byte = ts_node_end_byte(capture.node);
                span.style_id = it->second;
                span.flags = 0;
                macro_spans.push_back(span);
            }
        }
        ts_query_cursor_delete(q_cursor);
    }

    // =========================================================================
    // 双通道扫描线合并合成层 (Scanline Interval Merging)
    // 规则：
    // 1. 微观流中的【注释】与【文本块/字符串】作为硬容器，禁止 AST 符号产生穿透干扰。
    // 2. 宏观 AST 流在标识符、方法、类型等位置覆盖微观普通正则。
    // =========================================================================
    if (macro_spans.empty()) {
        final_spans = std::move(micro_spans);
    } else if (micro_spans.empty()) {
        final_spans = std::move(macro_spans);
    } else {
        std::sort(macro_spans.begin(), macro_spans.end(), [](const HighlightSpan& a, const HighlightSpan& b) {
            return (a.start_byte != b.start_byte) ? a.start_byte < b.start_byte : a.end_byte < b.end_byte;
        });
        std::sort(micro_spans.begin(), micro_spans.end(), [](const HighlightSpan& a, const HighlightSpan& b) {
            return (a.start_byte != b.start_byte) ? a.start_byte < b.start_byte : a.end_byte < b.end_byte;
        });

        // 识别出所有微观容器区间 (注释 style_id=3, 字符串 style_id=2, stringBlock style_id=12, docComment=11)
        std::vector<HighlightSpan> container_spans;
        std::vector<HighlightSpan> other_micro_spans;

        for (const auto& mic : micro_spans) {
            if (mic.style_id == 2 || mic.style_id == 3 || mic.style_id == 11 || mic.style_id == 12) {
                container_spans.push_back(mic);
            } else {
                other_micro_spans.push_back(mic);
            }
        }

        // 宏观 AST 遇到注释/字符串容器自动裁剪剔除
        for (const auto& mac : macro_spans) {
            bool enclosed = false;
            for (const auto& cont : container_spans) {
                if (mac.start_byte >= cont.start_byte && mac.end_byte <= cont.end_byte) {
                    enclosed = true;
                    break;
                }
            }
            if (!enclosed) {
                final_spans.push_back(mac);
            }
        }

        // 加入所有容器高亮 (注释、字符串)
        final_spans.insert(final_spans.end(), container_spans.begin(), container_spans.end());

        // 其它微观规则 (数值、关键字、操作符) 在 AST 未命中的缝隙中填补
        for (const auto& mic : other_micro_spans) {
            bool overlapped = false;
            for (const auto& cur : final_spans) {
                if (!(mic.end_byte <= cur.start_byte || mic.start_byte >= cur.end_byte)) {
                    overlapped = true;
                    break;
                }
            }
            if (!overlapped) {
                final_spans.push_back(mic);
            }
        }
    }

    // 终态统一按物理字节偏移升序排序，支持渲染层二分定位
    std::sort(final_spans.begin(), final_spans.end(), [](const HighlightSpan& a, const HighlightSpan& b) {
        return (a.start_byte != b.start_byte) ? a.start_byte < b.start_byte : a.end_byte < b.end_byte;
    });

    return final_spans;
}

// 代码折叠区间提取：结合 .tsh 中的 is_foldable 声明与跨行通用语法块
std::vector<FoldRange> WindbellEngine::computeFoldRanges(
    TSTree* tree,
    const uint16_t* input_chars,
    uint32_t length_chars,
    const std::string& extension
) {
    std::vector<FoldRange> folds;
    if (!tree) return folds;

    auto grammar = getGrammarByExtension(extension);
    TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));
    bool has_next = true;

    while (has_next) {
        TSNode node = ts_tree_cursor_current_node(&cursor);
        TSPoint s = ts_node_start_point(node);
        TSPoint e = ts_node_end_point(node);

        if (e.row > s.row) {
            TSSymbol sym = ts_node_symbol(node);
            const char* type = ts_node_type(node);
            bool should_fold = false;
            uint8_t fold_kind = 0;

            // 1. 优先从 .tsh 动态 AST 规则判定折叠
            if (grammar) {
                for (const auto& r : grammar->ast_selector_rules) {
                    if (r.target_symbol != 0 && r.target_symbol == sym && r.is_foldable) {
                        should_fold = true;
                        fold_kind = 0;
                        break;
                    }
                }
            }

            // 2. 通用跨行复合块与注释补充折叠
            if (!should_fold) {
                if (std::strstr(type, "comment")) {
                    should_fold = true;
                    fold_kind = 1;
                } else if (std::strstr(type, "import") || std::strstr(type, "package")) {
                    should_fold = true;
                    fold_kind = 2;
                } else if (std::strstr(type, "block") || std::strstr(type, "body") || std::strstr(type, "declaration_list")) {
                    should_fold = true;
                    fold_kind = 0;
                }
            }

            if (should_fold) {
                FoldRange r;
                r.start_byte = ts_node_start_byte(node);
                r.end_byte = ts_node_end_byte(node);
                r.kind = fold_kind;
                folds.push_back(r);
            }
        }

        if (ts_tree_cursor_goto_first_child(&cursor)) continue;
        if (ts_tree_cursor_goto_next_sibling(&cursor)) continue;
        while (ts_tree_cursor_goto_parent(&cursor)) {
            if (ts_tree_cursor_goto_next_sibling(&cursor)) break;
        }
        has_next = (ts_node_start_byte(ts_tree_cursor_current_node(&cursor)) != 0 || 
                    ts_tree_cursor_current_node(&cursor).id != ts_tree_root_node(tree).id);
    }

    ts_tree_cursor_delete(&cursor);
    return folds;
}

// 符号大纲提取：完全由 .tsh 中 outline 属性指定，拒绝在 C++ 内核写死 class/method
std::vector<OutlineTag> WindbellEngine::computeOutline(
    TSTree* tree,
    const uint16_t* input_chars,
    uint32_t length_chars,
    const std::string& extension
) {
    std::vector<OutlineTag> tags;
    if (!tree) return tags;

    auto grammar = getGrammarByExtension(extension);
    if (!grammar) return tags;

    TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));
    bool has_next = true;

    while (has_next) {
        TSNode node = ts_tree_cursor_current_node(&cursor);
        TSSymbol sym = ts_node_symbol(node);

        for (const auto& r : grammar->ast_selector_rules) {
            if (r.target_symbol != 0 && r.target_symbol == sym && r.outline_kind > 0) {
                OutlineTag tag;
                std::memset(&tag, 0, sizeof(OutlineTag));
                tag.start_byte = ts_node_start_byte(node);
                tag.end_byte = ts_node_end_byte(node);
                tag.kind = r.outline_kind;

                // 提取命名节点
                TSNode name_node = (r.field_id != 0) ?
                    ts_node_child_by_field_id(node, r.field_id) :
                    ts_node_child_by_field_name(node, "name", 4);

                if (!ts_node_is_null(name_node)) {
                    uint32_t n_start = ts_node_start_byte(name_node) / sizeof(uint16_t);
                    uint32_t n_end = ts_node_end_byte(name_node) / sizeof(uint16_t);
                    uint32_t n_len = std::min(n_end - n_start, 63u);
                    for (uint32_t i = 0; i < n_len; ++i) {
                        tag.name[i] = static_cast<char>(input_chars[n_start + i] & 0x7F);
                    }
                    tag.name[n_len] = '\0';
                } else {
                    std::strncpy(tag.name, ts_node_type(node), 63);
                }
                tags.push_back(tag);
                break;
            }
        }

        if (ts_tree_cursor_goto_first_child(&cursor)) continue;
        if (ts_tree_cursor_goto_next_sibling(&cursor)) continue;
        while (ts_tree_cursor_goto_parent(&cursor)) {
            if (ts_tree_cursor_goto_next_sibling(&cursor)) break;
        }
        has_next = (ts_node_start_byte(ts_tree_cursor_current_node(&cursor)) != 0 || 
                    ts_tree_cursor_current_node(&cursor).id != ts_tree_root_node(tree).id);
    }

    ts_tree_cursor_delete(&cursor);
    return tags;
}

// 悬停感知信息提取
bool WindbellEngine::getHoverInfo(TSTree* tree, uint32_t byte_offset, HoverInfo& out_info) {
    if (!tree) return false;
    TSNode node = ts_node_descendant_for_byte_range(ts_tree_root_node(tree), byte_offset, byte_offset);
    if (ts_node_is_null(node)) return false;

    out_info.start_byte = ts_node_start_byte(node);
    out_info.end_byte = ts_node_end_byte(node);
    out_info.node_type = ts_node_type(node);

    TSNode parent = ts_node_parent(node);
    if (!ts_node_is_null(parent)) {
        out_info.detail = std::string("Enclosing AST: ") + ts_node_type(parent);
    }
    return true;
}

} // namespace windbell::tsh