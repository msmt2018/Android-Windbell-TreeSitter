/*
 * Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
 * 双流水线调度引擎与扫描线合成器完整实现
 */

#include "WindbellTshCore.h"
#include <algorithm>
#include <cstring>
#include <functional>
#include "ts__log.h"

namespace windbell::tsh {

TshGrammar::~TshGrammar() {
    std::function<void(CompiledRegexRule&)> release_rule;
    release_rule = [&](CompiledRegexRule& rule) {
        for (auto& nested : rule.nested_rules) release_rule(nested);
        if (rule.onig_start) {
            onig_free(rule.onig_start);
            rule.onig_start = nullptr;
        }
        if (rule.onig_end) {
            onig_free(rule.onig_end);
            rule.onig_end = nullptr;
        }
    };
    for (auto& rule : regex_rules) release_rule(rule);

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
            block_span.priority = rule.priority;
            block_span.declaration_order = rule.declaration_order;
            out_spans.push_back(block_span);

            if (!rule.nested_rules.empty() && hit_end >= 0) {
                const UChar* inner_start = content_start;
                const UChar* inner_end = text_start + end_region->beg[0];

                for (const auto& nested : rule.nested_rules) {
                    if (!nested.enabled) continue;
                    if (nested.type == RegexRuleType::BLOCK && nested.onig_start && nested.onig_end) {
                        matchBlockRecursive(nested, text_start, text_end, inner_start, inner_end, out_spans);
                        continue;
                    }
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

                            auto append_nested_capture = [&](int group, uint16_t style_id) {
                                if (group < 0 || group >= inner_region->num_regs ||
                                    inner_region->beg[group] < 0 || inner_region->end[group] < 0) return;
                                const uint32_t in_s = static_cast<uint32_t>(inner_region->beg[group]);
                                const uint32_t in_e = static_cast<uint32_t>(inner_region->end[group]);
                                if ((in_s & 1u) != 0 || (in_e & 1u) != 0) return;
                                HighlightSpan in_span;
                                in_span.start_byte = in_s;
                                in_span.end_byte = in_e;
                                in_span.style_id = style_id;
                                in_span.flags = 0;
                                in_span.priority = nested.priority;
                                in_span.declaration_order = nested.declaration_order;
                                out_spans.push_back(in_span);
                            };
                            if (nested.group_style_map.empty()) {
                                if (nested.has_style) append_nested_capture(0, nested.style_id);
                            } else {
                                auto whole = nested.group_style_map.find(0);
                                if (whole != nested.group_style_map.end()) append_nested_capture(0, whole->second);
                                for (int group = 1; group < inner_region->num_regs; ++group) {
                                    auto style = nested.group_style_map.find(group);
                                    if (style != nested.group_style_map.end()) {
                                        append_nested_capture(group, style->second);
                                    }
                                }
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
    micro_spans.reserve(length_chars / 4);

    for (const auto& rule : grammar->regex_rules) {
        if (!rule.enabled) continue;
        if (rule.type == RegexRuleType::BLOCK && rule.has_style && rule.onig_start && rule.onig_end) {
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
                    auto append_capture = [&](int group, uint16_t style_id) {
                        if (group < 0 || group >= region->num_regs ||
                            region->beg[group] < 0 || region->end[group] < 0) return;
                        HighlightSpan capture;
                        capture.start_byte = static_cast<uint32_t>(region->beg[group]);
                        capture.end_byte = static_cast<uint32_t>(region->end[group]);
                        capture.style_id = style_id;
                        capture.flags = 0;
                        capture.priority = rule.priority;
                        capture.declaration_order = rule.declaration_order;
                        micro_spans.push_back(capture);
                    };

                    if (rule.group_style_map.empty()) {
                        if (rule.has_style) append_capture(0, rule.style_id);
                    } else {
                        auto whole_match = rule.group_style_map.find(0);
                        if (whole_match != rule.group_style_map.end()) {
                            append_capture(0, whole_match->second);
                        }
                        for (int g = 1; g < region->num_regs; ++g) {
                            auto style_it = rule.group_style_map.find(g);
                            if (style_it != rule.group_style_map.end()) {
                                append_capture(g, style_it->second);
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
        macro_spans.reserve(length_chars / 6);
        TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));

        while (true) {
            TSNode node = ts_tree_cursor_current_node(&cursor);
            TSSymbol sym = ts_node_symbol(node);
            TSFieldId fid = ts_tree_cursor_current_field_id(&cursor);

            TSNode parent = ts_node_parent(node);
            TSSymbol parent_sym = ts_node_is_null(parent) ? 0 : ts_node_symbol(parent);

            for (const auto& ast_rule : grammar->ast_selector_rules) {
                if (!ast_rule.enabled) continue;
                // A selector with a field names the owner node and styles the
                // selected child. A selector without a field targets the node itself.
                if (ast_rule.field_id != 0) {
                    if (ts_node_is_null(parent) ||
                        (ast_rule.target_symbol != 0 && ast_rule.target_symbol != parent_sym) ||
                        ast_rule.field_id != fid) {
                        continue;
                    }
                } else if (ast_rule.target_symbol != 0 && ast_rule.target_symbol != sym) {
                    continue;
                }
                if (ast_rule.parent_symbol != 0 && ast_rule.parent_symbol != parent_sym) {
                    continue;
                }

                if (ast_rule.has_style) {
                    HighlightSpan span;
                    span.start_byte = ts_node_start_byte(node);
                    span.end_byte = ts_node_end_byte(node);
                    span.style_id = ast_rule.style_id;
                    span.flags = 0;
                    span.priority = ast_rule.priority;
                    span.declaration_order = ast_rule.declaration_order;
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
                span.priority = 0;
                span.declaration_order = capture_index;
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

        // Keep overlapping captures. Their precedence is a rule-level concern,
        // not an accidental consequence of numeric style IDs.
        final_spans.reserve(macro_spans.size() + micro_spans.size());
        final_spans.insert(final_spans.end(), macro_spans.begin(), macro_spans.end());
        final_spans.insert(final_spans.end(), micro_spans.begin(), micro_spans.end());
    }

    std::stable_sort(final_spans.begin(), final_spans.end(), [](const HighlightSpan& a, const HighlightSpan& b) {
        if (a.start_byte != b.start_byte) return a.start_byte < b.start_byte;
        // Apply outer captures first, then inner/high-priority captures.
        if (a.end_byte != b.end_byte) return a.end_byte > b.end_byte;
        if (a.priority != b.priority) return a.priority < b.priority;
        return a.declaration_order < b.declaration_order;
    });

    return final_spans;
}

} // namespace windbell::tsh