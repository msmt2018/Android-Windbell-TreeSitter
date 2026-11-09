/*
 * Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
 * 双流水线调度引擎与扫描线合成器完整实现
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

        uint32_t block_start_byte = static_cast<uint32_t>(start_region->beg[0]);
        const UChar* content_start = text_start + start_region->end[0];

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

        if ((block_start_byte & 1u) == 0 && (block_end_byte & 1u) == 0) {
            HighlightSpan block_span;
            block_span.start_byte = block_start_byte;
            block_span.end_byte = block_end_byte;
            block_span.style_id = rule.style_id;
            block_span.flags = 0;
            out_spans.push_back(block_span);

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

    std::vector<HighlightSpan> micro_spans;
    // Avoid reserving proportional to the whole document: sparse rules on a 300 MB file
    // must not pre-allocate hundreds of megabytes of span capacity.
    micro_spans.reserve(std::min<size_t>(length_chars / 128u, 4096u));

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

    std::vector<HighlightSpan> macro_spans;
    if (tree && !grammar->ast_selector_rules.empty()) {
        macro_spans.reserve(std::min<size_t>(length_chars / 256u, 2048u));
        TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));

        while (true) {
            TSNode node = ts_tree_cursor_current_node(&cursor);
            TSSymbol sym = ts_node_symbol(node);
            TSFieldId fid = ts_tree_cursor_current_field_id(&cursor);

            TSNode parent = ts_node_parent(node);
            TSSymbol parent_sym = ts_node_is_null(parent) ? 0 : ts_node_symbol(parent);

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

            if (ts_tree_cursor_goto_first_child(&cursor)) continue;
            if (ts_tree_cursor_goto_next_sibling(&cursor)) continue;
            
            bool retraced = false;
            while (ts_tree_cursor_goto_parent(&cursor)) {
                if (ts_tree_cursor_goto_next_sibling(&cursor)) {
                    retraced = true;
                    break;
                }
            }
            if (!retraced) {
                break;
            }
        }
        ts_tree_cursor_delete(&cursor);
    }

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

        std::vector<HighlightSpan> container_spans;
        std::vector<HighlightSpan> other_micro_spans;

        for (const auto& mic : micro_spans) {
            if (mic.style_id == 2 || mic.style_id == 3 || mic.style_id == 11 || mic.style_id == 12) {
                container_spans.push_back(mic);
            } else {
                other_micro_spans.push_back(mic);
            }
        }

        // Container containment lookup: prefix maximum end makes each lookup O(log n)
        // rather than scanning every container for every AST span.
        std::vector<uint32_t> container_prefix_max_end;
        container_prefix_max_end.reserve(container_spans.size());
        uint32_t max_container_end = 0;
        for (const auto& container : container_spans) {
            max_container_end = std::max(max_container_end, container.end_byte);
            container_prefix_max_end.push_back(max_container_end);
        }

        for (const auto& mac : macro_spans) {
            const auto upper = std::upper_bound(
                container_spans.begin(), container_spans.end(), mac.start_byte,
                [](uint32_t start, const HighlightSpan& container) {
                    return start < container.start_byte;
                }
            );
            bool enclosed = false;
            if (upper != container_spans.begin()) {
                const size_t index = static_cast<size_t>(upper - container_spans.begin() - 1);
                enclosed = container_prefix_max_end[index] >= mac.end_byte;
            }
            if (!enclosed) final_spans.push_back(mac);
        }

        final_spans.insert(final_spans.end(), container_spans.begin(), container_spans.end());
        std::sort(final_spans.begin(), final_spans.end(), [](const HighlightSpan& a, const HighlightSpan& b) {
            return (a.start_byte != b.start_byte) ? a.start_byte < b.start_byte : a.end_byte < b.end_byte;
        });

        // Overlap lookup uses a prefix maximum-end index. It avoids the former O(m*n)
        // nested scan when both regex and AST produce many spans in a large source file.
        std::vector<uint32_t> final_prefix_max_end;
        final_prefix_max_end.reserve(final_spans.size());
        uint32_t max_final_end = 0;
        for (const auto& current_span : final_spans) {
            max_final_end = std::max(max_final_end, current_span.end_byte);
            final_prefix_max_end.push_back(max_final_end);
        }

        for (const auto& mic : other_micro_spans) {
            bool overlapped = false;
            const auto upper = std::upper_bound(
                final_spans.begin(), final_spans.end(), mic.start_byte,
                [](uint32_t start, const HighlightSpan& current_span) {
                    return start < current_span.start_byte;
                }
            );
            if (upper != final_spans.begin()) {
                const size_t previous = static_cast<size_t>(upper - final_spans.begin() - 1);
                overlapped = final_prefix_max_end[previous] > mic.start_byte;
            }
            if (!overlapped) {
                const auto end = std::lower_bound(
                    upper, final_spans.end(), mic.end_byte,
                    [](const HighlightSpan& current_span, uint32_t end_byte) {
                        return current_span.start_byte < end_byte;
                    }
                );
                for (auto it = upper; it != end; ++it) {
                    if (it->end_byte > mic.start_byte) {
                        overlapped = true;
                        break;
                    }
                }
            }
            if (!overlapped) final_spans.push_back(mic);
        }
    }

    std::sort(final_spans.begin(), final_spans.end(), [](const HighlightSpan& a, const HighlightSpan& b) {
        return (a.start_byte != b.start_byte) ? a.start_byte < b.start_byte : a.end_byte < b.end_byte;
    });

    return final_spans;
}

std::vector<FoldRange> WindbellEngine::computeFoldRanges(
    TSTree* tree,
    const uint16_t* input_chars,
    uint32_t length_chars,
    const std::string& extension
) {
    std::vector<FoldRange> folds;
    if (!tree) return folds;

    std::shared_ptr<TshGrammar> grammar = nullptr;
    if (!extension.empty()) {
        grammar = getGrammarByExtension(extension);
    }
    if (!grammar) {
        const TSLanguage* lang = ts_tree_language(tree);
        std::lock_guard<std::mutex> lock(registry_lock_);
        for (const auto& pair : grammar_registry_) {
            if (pair.second && pair.second->ts_language == lang) {
                grammar = pair.second;
                break;
            }
        }
        if (!grammar && !grammar_registry_.empty()) {
            grammar = grammar_registry_.begin()->second;
        }
    }

    TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));

    while (true) {
        TSNode node = ts_tree_cursor_current_node(&cursor);
        TSPoint s = ts_node_start_point(node);
        TSPoint e = ts_node_end_point(node);

        if (e.row > s.row) {
            TSSymbol sym = ts_node_symbol(node);
            const char* type = ts_node_type(node);
            bool should_fold = false;
            uint8_t fold_kind = 0;

            if (grammar) {
                for (const auto& r : grammar->ast_selector_rules) {
                    if (r.target_symbol != 0 && r.target_symbol == sym && r.is_foldable) {
                        should_fold = true;
                        fold_kind = 0;
                        break;
                    }
                }
            }

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
        
        bool retraced = false;
        while (ts_tree_cursor_goto_parent(&cursor)) {
            if (ts_tree_cursor_goto_next_sibling(&cursor)) {
                retraced = true;
                break;
            }
        }
        if (!retraced) break;
    }

    ts_tree_cursor_delete(&cursor);
    return folds;
}

std::vector<OutlineTag> WindbellEngine::computeOutline(
    TSTree* tree,
    const uint16_t* input_chars,
    uint32_t length_chars,
    const std::string& extension
) {
    std::vector<OutlineTag> tags;
    if (!tree) return tags;

    std::shared_ptr<TshGrammar> grammar = nullptr;
    if (!extension.empty()) {
        grammar = getGrammarByExtension(extension);
    }
    if (!grammar) {
        const TSLanguage* lang = ts_tree_language(tree);
        std::lock_guard<std::mutex> lock(registry_lock_);
        for (const auto& pair : grammar_registry_) {
            if (pair.second && pair.second->ts_language == lang) {
                grammar = pair.second;
                break;
            }
        }
        if (!grammar && !grammar_registry_.empty()) {
            grammar = grammar_registry_.begin()->second;
        }
    }

    if (!grammar) return tags;

    TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));

    while (true) {
        TSNode node = ts_tree_cursor_current_node(&cursor);
        TSSymbol sym = ts_node_symbol(node);

        for (const auto& r : grammar->ast_selector_rules) {
            if (r.target_symbol != 0 && r.target_symbol == sym && r.outline_kind > 0) {
                OutlineTag tag;
                std::memset(&tag, 0, sizeof(OutlineTag));
                tag.start_byte = ts_node_start_byte(node);
                tag.end_byte = ts_node_end_byte(node);
                tag.kind = r.outline_kind;

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
        
        bool retraced = false;
        while (ts_tree_cursor_goto_parent(&cursor)) {
            if (ts_tree_cursor_goto_next_sibling(&cursor)) {
                retraced = true;
                break;
            }
        }
        if (!retraced) break;
    }

    ts_tree_cursor_delete(&cursor);
    return tags;
}

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