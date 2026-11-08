/*
 * Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
 * 双流水线调度引擎与扫描线合成器完整实现
 */

#include "WindbellTshCore.h"
#include <algorithm>
#include <cstring>
#include <limits>
#include <functional>
#include <unordered_set>
#include <string_view>
#include "ts__log.h"

namespace windbell::tsh {

static const UChar* advanceOneUtf16CodePoint(const UChar* current, const UChar* end) {
    const auto* bytes = reinterpret_cast<const uint8_t*>(current);
    const auto* end_bytes = reinterpret_cast<const uint8_t*>(end);
    if (bytes + 2 > end_bytes) return end;
    const uint16_t first = static_cast<uint16_t>(bytes[0]) |
                           (static_cast<uint16_t>(bytes[1]) << 8);
    bytes += 2;
    if (first >= 0xD800 && first <= 0xDBFF && bytes + 2 <= end_bytes) {
        const uint16_t second = static_cast<uint16_t>(bytes[0]) |
                                (static_cast<uint16_t>(bytes[1]) << 8);
        if (second >= 0xDC00 && second <= 0xDFFF) bytes += 2;
    }
    return reinterpret_cast<const UChar*>(bytes);
}


// Convert only the requested UTF-16LE slice. Building a UTF-8 copy and a
// byte-for-byte offset map for the entire document multiplies peak memory for
// large files; AST text predicates need conversion only for the nodes they test.
static bool tshUtf16SliceToUtf8(
    const UChar* input,
    uint32_t input_bytes,
    std::string& utf8
) {
    utf8.clear();
    if ((input_bytes & 1u) != 0 || (!input && input_bytes != 0)) return false;
    utf8.reserve(input_bytes);
    for (uint32_t i = 0; i < input_bytes;) {
        const uint16_t first = static_cast<uint16_t>(input[i]) |
            (static_cast<uint16_t>(input[i + 1]) << 8);
        i += 2;
        uint32_t cp = first;
        if (first >= 0xD800 && first <= 0xDBFF) {
            if (i + 1 >= input_bytes) return false;
            const uint16_t second = static_cast<uint16_t>(input[i]) |
                (static_cast<uint16_t>(input[i + 1]) << 8);
            if (second < 0xDC00 || second > 0xDFFF) return false;
            i += 2;
            cp = 0x10000 + ((static_cast<uint32_t>(first) - 0xD800) << 10) +
                (static_cast<uint32_t>(second) - 0xDC00);
        } else if (first >= 0xDC00 && first <= 0xDFFF) {
            return false;
        }

        if (cp <= 0x7F) {
            utf8.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FF) {
            utf8.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            utf8.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            utf8.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            utf8.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            utf8.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    return true;
}

static bool tshUtf8ToUtf16(const std::string& input, std::vector<UChar>& output) {
    output.clear();
    for (size_t i = 0; i < input.size();) {
        const uint8_t first = static_cast<uint8_t>(input[i++]);
        uint32_t cp = 0;
        size_t continuation = 0;
        if (first <= 0x7F) cp = first;
        else if ((first & 0xE0) == 0xC0) { cp = first & 0x1F; continuation = 1; }
        else if ((first & 0xF0) == 0xE0) { cp = first & 0x0F; continuation = 2; }
        else if ((first & 0xF8) == 0xF0) { cp = first & 0x07; continuation = 3; }
        else return false;
        if (i + continuation > input.size()) return false;
        for (size_t j = 0; j < continuation; ++j) {
            const uint8_t next = static_cast<uint8_t>(input[i++]);
            if ((next & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (next & 0x3F);
        }
        if ((continuation == 1 && cp < 0x80) || (continuation == 2 && cp < 0x800) ||
            (continuation == 3 && cp < 0x10000) || cp > 0x10FFFF ||
            (cp >= 0xD800 && cp <= 0xDFFF)) return false;
        auto append_utf16le_unit = [&](uint16_t unit) {
            output.push_back(static_cast<UChar>(unit & 0xFF));
            output.push_back(static_cast<UChar>((unit >> 8) & 0xFF));
        };
        if (cp <= 0xFFFF) append_utf16le_unit(static_cast<uint16_t>(cp));
        else {
            cp -= 0x10000;
            append_utf16le_unit(static_cast<uint16_t>(0xD800 + (cp >> 10)));
            append_utf16le_unit(static_cast<uint16_t>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return true;
}

static bool tshPredicateCaptureTexts(const TSQueryMatch& match, uint32_t capture_id,
    const UChar* utf16_source,
    uint32_t source_bytes,
    std::vector<std::vector<UChar>>& texts) {
    texts.clear();
    for (uint32_t i = 0; i < match.capture_count; ++i) {
        if (match.captures[i].index != capture_id) continue;
        const uint32_t start = ts_node_start_byte(match.captures[i].node);
        const uint32_t end = ts_node_end_byte(match.captures[i].node);
        if (start > end || end > source_bytes || (start & 1u) || (end & 1u) ||
            (!utf16_source && end != start)) return false;
        std::vector<UChar> converted;
        if (end > start) converted.assign(utf16_source + start, utf16_source + end);
        texts.push_back(std::move(converted));
    }
    // A query may legitimately omit an optional capture. Negative predicates
    // still evaluate successfully for an absent capture, matching Tree-sitter's
    // predicate semantics; the operator decides the empty-set result.
    return true;
}

static bool tshEvaluateNativePredicates(const NativeQueryProgram& program,
    const TSQueryMatch& match, const UChar* utf16_source, uint32_t source_bytes) {
    const TSQuery* query = program.query;
    uint32_t step_count = 0;
    const TSQueryPredicateStep* steps =
        ts_query_predicates_for_pattern(query, match.pattern_index, &step_count);
    if (!steps || step_count == 0) return true;
    uint32_t cursor = 0;
    uint32_t predicate_index = 0;
    while (cursor < step_count) {
        std::vector<TSQueryPredicateStep> predicate;
        while (cursor < step_count && steps[cursor].type != TSQueryPredicateStepTypeDone)
            predicate.push_back(steps[cursor++]);
        if (cursor < step_count) ++cursor;
        if (predicate.empty() || predicate[0].type != TSQueryPredicateStepTypeString) return false;

        uint32_t op_length = 0;
        const char* op_text = ts_query_string_value_for_id(query, predicate[0].value_id, &op_length);
        if (!op_text || predicate.size() < 3 ||
            predicate[1].type != TSQueryPredicateStepTypeCapture) return false;
        std::string op(op_text, op_length);
        if (!op.empty() && op.front() == '#') op.erase(op.begin());

        std::vector<std::vector<UChar>> actual;
        if (!tshPredicateCaptureTexts(match, predicate[1].value_id, utf16_source, source_bytes, actual))
            return false;
        std::vector<std::vector<UChar>> expected;
        for (size_t i = 2; i < predicate.size(); ++i) {
            if (predicate[i].type != TSQueryPredicateStepTypeString) return false;
            uint32_t length = 0;
            const char* value = ts_query_string_value_for_id(query, predicate[i].value_id, &length);
            std::vector<UChar> converted;
            if (!value || !tshUtf8ToUtf16(std::string(value, length), converted)) return false;
            expected.push_back(std::move(converted));
        }
        if (expected.empty()) return false;
        bool accepted = false;
        if (op == "eq?" || op == "not-eq?") {
            if (expected.size() != 1) return false;
            const bool all_equal = !actual.empty() && std::all_of(actual.begin(), actual.end(),
                [&](const std::vector<UChar>& value) { return value == expected.front(); });
            const bool all_not_equal = std::all_of(actual.begin(), actual.end(),
                [&](const std::vector<UChar>& value) { return value != expected.front(); });
            accepted = op == "eq?" ? all_equal : all_not_equal;
        } else if (op == "any-of?" || op == "not-any-of?") {
            const bool all_in_set = std::all_of(actual.begin(), actual.end(),
                [&](const std::vector<UChar>& value) {
                    return std::find(expected.begin(), expected.end(), value) != expected.end();
                });
            accepted = op == "any-of?" ? all_in_set :
                std::none_of(actual.begin(), actual.end(), [&](const std::vector<UChar>& value) {
                    return std::find(expected.begin(), expected.end(), value) != expected.end();
                });
        } else if (op == "match?" || op == "not-match?") {
            const NativeQueryProgram::PredicateRegex* compiled = nullptr;
            for (const auto& candidate : program.predicate_regexes) {
                if (candidate.pattern_index == match.pattern_index &&
                    candidate.predicate_index == predicate_index &&
                    candidate.capture_id == predicate[1].value_id) {
                    compiled = &candidate;
                    break;
                }
            }
            if (!compiled || !compiled->regex) return false;
            const auto matches_regex = [&](const std::vector<UChar>& value) {
                if (value.empty()) return false;
                OnigRegion* region = onig_region_new();
                if (!region) return false;
                const UChar* begin = value.data();
                const UChar* end = begin + value.size();
                const int result = onig_search(compiled->regex, begin, end, begin, end,
                    region, ONIG_OPTION_NONE);
                onig_region_free(region, 1);
                return result >= 0;
            };
            const bool all_match = !actual.empty() &&
                std::all_of(actual.begin(), actual.end(), matches_regex);
            const bool all_not_match = std::all_of(actual.begin(), actual.end(),
                [&](const std::vector<UChar>& value) { return !matches_regex(value); });
            accepted = compiled->negate ? all_not_match : all_match;
        } else return false;
        if (!accepted) return false;
        ++predicate_index;
    }
    return true;
}

static void appendRegexCaptureSpans(
    regex_t* regex,
    OnigRegion* region,
    const std::unordered_map<int, uint16_t>& numeric_styles,
    const std::unordered_map<std::string, uint16_t>& named_styles,
    int32_t priority,
    uint32_t declaration_order,
    bool allow_overlap,
    std::vector<HighlightSpan>& output
) {
    if (!regex || !region) return;
    auto append = [&](int group, uint16_t style_id) {
        if (group < 0 || group >= region->num_regs ||
            region->beg[group] < 0 || region->end[group] < 0) return;
        const uint32_t start = static_cast<uint32_t>(region->beg[group]);
        const uint32_t end = static_cast<uint32_t>(region->end[group]);
        if ((start & 1u) || (end & 1u) || start >= end) return;
        HighlightSpan span;
        span.start_byte = start;
        span.end_byte = end;
        span.style_id = style_id;
        span.flags = 0;
        span.priority = priority;
        span.declaration_order = declaration_order;
        span.allow_overlap = allow_overlap ? 1 : 0;
        output.push_back(span);
    };
    for (const auto& entry : numeric_styles) append(entry.first, entry.second);
    for (const auto& entry : named_styles) {
        std::vector<uint16_t> encoded_name(entry.first.begin(), entry.first.end());
        const int group = onig_name_to_backref_number(
            regex,
            reinterpret_cast<const UChar*>(encoded_name.data()),
            reinterpret_cast<const UChar*>(encoded_name.data() + encoded_name.size()),
            region
        );
        append(group, entry.second);
    }
}

TshGrammar::~TshGrammar() {
    // firstLine is compiled independently from rule patterns and must be
    // released with the grammar snapshot that owns it.
    if (first_line_regex) {
        onig_free(first_line_regex);
        first_line_regex = nullptr;
    }

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
    for (auto& state : monarch_states) {
        for (auto& rule : state.second) {
            if (rule.pattern) {
                onig_free(rule.pattern);
                rule.pattern = nullptr;
            }
            for (auto& entry : rule.cases) {
                if (entry.matcher) {
                    onig_free(entry.matcher);
                    entry.matcher = nullptr;
                }
            }
        }
    }

    for (auto& ast_rule : ast_selector_rules) {
        if (ast_rule.text_regex) {
            onig_free(ast_rule.text_regex);
            ast_rule.text_regex = nullptr;
        }
    }

    for (auto& native_query : native_queries) {
        for (auto& predicate : native_query.predicate_regexes) {
            if (predicate.regex) {
                onig_free(predicate.regex);
                predicate.regex = nullptr;
            }
        }
        if (native_query.query) {
            ts_query_delete(native_query.query);
            native_query.query = nullptr;
        }
    }
    native_queries.clear();
}

WindbellEngine::WindbellEngine() {
    OnigEncoding encodings[] = { ONIG_ENCODING_UTF16_LE };
    onig_initialize(encodings, 1);
}

WindbellEngine::~WindbellEngine() {
    // Destroy compiled regex objects before shutting down the Oniguruma runtime.
    {
        std::lock_guard<std::mutex> lock(registry_lock_);
        grammar_registry_.clear();
    }
    onig_end();
}

bool WindbellEngine::registerGrammar(const std::string& extension, std::shared_ptr<TshGrammar> grammar) {
    std::lock_guard<std::mutex> lock(registry_lock_);
    grammar_registry_[extension] = grammar;
    return true;
}

std::shared_ptr<const TshGrammar> WindbellEngine::getGrammarByExtension(const std::string& extension) {
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
    std::vector<HighlightSpan>& out_spans,
    uint32_t* regex_match_count
) {
    OnigRegion* start_region = onig_region_new();
    OnigRegion* end_region = onig_region_new();
    if (!start_region || !end_region) {
        if (start_region) onig_region_free(start_region, 1);
        if (end_region) onig_region_free(end_region, 1);
        return;
    }
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
        if (regex_match_count) ++(*regex_match_count);

        uint32_t block_start_byte = static_cast<uint32_t>(start_region->beg[0]);
        const UChar* content_start = text_start + start_region->end[0];

        int hit_end = onig_search(
            rule.onig_end,
            text_start,
            text_end,
            content_start,
            range_end,
            end_region,
            ONIG_OPTION_NONE
        );

        uint32_t block_end_byte = (hit_end >= 0) ?
            static_cast<uint32_t>(end_region->end[0]) :
            static_cast<uint32_t>(range_end - text_start);

        if ((block_start_byte & 1u) == 0 && (block_end_byte & 1u) == 0) {
            if (rule.has_style && block_start_byte < block_end_byte) {
                HighlightSpan block_span;
                block_span.start_byte = block_start_byte;
                block_span.end_byte = block_end_byte;
                block_span.style_id = rule.style_id;
                block_span.flags = 0;
                block_span.priority = rule.priority;
                block_span.declaration_order = rule.declaration_order;
                block_span.allow_overlap = rule.allow_overlap ? 1 : 0;
                out_spans.push_back(block_span);
            }
            if (rule.has_content_style) {
                const uint32_t content_start = static_cast<uint32_t>(start_region->end[0]);
                const uint32_t content_end = hit_end >= 0 ?
                    static_cast<uint32_t>(end_region->beg[0]) :
                    static_cast<uint32_t>(range_end - text_start);
                if (content_start < content_end &&
                    (content_start & 1u) == 0 && (content_end & 1u) == 0) {
                    HighlightSpan content_span;
                    content_span.start_byte = content_start;
                    content_span.end_byte = content_end;
                    content_span.style_id = rule.content_style_id;
                    content_span.priority = rule.priority;
                    content_span.declaration_order = rule.declaration_order;
                    content_span.allow_overlap = rule.allow_overlap ? 1 : 0;
                    out_spans.push_back(content_span);
                }
            }
            appendRegexCaptureSpans(
                rule.onig_start, start_region, rule.start_group_style_map,
                rule.start_named_group_style_map, rule.priority, rule.declaration_order,
                rule.allow_overlap, out_spans
            );
            if (hit_end >= 0) {
                appendRegexCaptureSpans(
                    rule.onig_end, end_region, rule.end_group_style_map,
                    rule.end_named_group_style_map, rule.priority, rule.declaration_order,
                    rule.allow_overlap, out_spans
                );
            }

            if (!rule.nested_rules.empty()) {
                const UChar* inner_start = content_start;
                const UChar* inner_end = hit_end >= 0 ? text_start + end_region->beg[0] : range_end;

                for (const auto& nested : rule.nested_rules) {
                    if (!nested.enabled) continue;
                    if (nested.type == RegexRuleType::BLOCK && nested.onig_start && nested.onig_end) {
                        matchBlockRecursive(nested, text_start, text_end, inner_start, inner_end, out_spans, regex_match_count);
                        continue;
                    }
                    if (nested.type == RegexRuleType::MATCH && nested.onig_start) {
                        OnigRegion* inner_region = onig_region_new();
                        if (!inner_region) continue;
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
                            if (regex_match_count) ++(*regex_match_count);

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
                                in_span.allow_overlap = nested.allow_overlap ? 1 : 0;
                                out_spans.push_back(in_span);
                            };
                            if (nested.group_style_map.empty() && nested.named_group_style_map.empty()) {
                                if (nested.has_style) append_nested_capture(0, nested.style_id);
                            } else {
                                auto whole = nested.group_style_map.find(0);
                                if (whole != nested.group_style_map.end()) append_nested_capture(0, whole->second);
                                for (int group = 1; group < inner_region->num_regs; ++group) {
                                    auto style = nested.group_style_map.find(group);
                                    if (style != nested.group_style_map.end()) append_nested_capture(group, style->second);
                                }
                                for (const auto& named : nested.named_group_style_map) {
                                    std::vector<uint16_t> encoded_name(named.first.begin(), named.first.end());
                                    int group = onig_name_to_backref_number(
                                        nested.onig_start,
                                        reinterpret_cast<const UChar*>(encoded_name.data()),
                                        reinterpret_cast<const UChar*>(encoded_name.data() + encoded_name.size()),
                                        inner_region);
                                    append_nested_capture(group, named.second);
                                }
                            }
                            inner_curr = (inner_region->end[0] <= inner_region->beg[0]) ?
                                advanceOneUtf16CodePoint(text_start + inner_region->beg[0], inner_end) :
                                text_start + inner_region->end[0];
                        }
                        onig_region_free(inner_region, 1);
                    }
                }
            }
        }

        if (hit_end >= 0 && end_region->end[0] > start_region->beg[0]) {
            const UChar* next = text_start + end_region->end[0];
            if (next <= curr) next = advanceOneUtf16CodePoint(curr, range_end);
            curr = next;
        } else {
            curr = advanceOneUtf16CodePoint(text_start + start_region->end[0], range_end);
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
    const std::string& extension,
    std::vector<HighlightSpan>* diagnostic_spans,
    uint32_t* diagnostic_match_counts,
    uint32_t range_start_byte,
    uint32_t range_end_byte
) {
    std::vector<HighlightSpan> final_spans;
    if (diagnostic_match_counts) std::fill(diagnostic_match_counts, diagnostic_match_counts + 4, 0u);
    if (length_chars != 0 && input_chars == nullptr) return final_spans;
    if (length_chars > (std::numeric_limits<uint32_t>::max() / sizeof(uint16_t))) return final_spans;
    auto grammar = getGrammarByExtension(extension);
    if (!grammar) return final_spans;

    uint32_t total_bytes = length_chars * sizeof(uint16_t);
    if (range_end_byte == UINT32_MAX) range_end_byte = total_bytes;
    if ((range_start_byte & 1u) != 0 || (range_end_byte & 1u) != 0 ||
        range_start_byte > range_end_byte || range_end_byte > total_bytes) {
        return final_spans;
    }
    // Avoid pointer arithmetic on a null input pointer for an empty document.
    // Empty buffers are valid inputs (for example, immediately after clearing an editor).
    static const UChar kEmptyInput = 0;
    const UChar* text_start = input_chars
        ? reinterpret_cast<const UChar*>(input_chars)
        : &kEmptyInput;
    const UChar* text_end = text_start + total_bytes;

    // Keep intermediate capture memory proportional to the requested viewport.
    // Regex and Monarch still scan from document start to preserve anchors and
    // tokenizer state, but captures outside this byte interval are discarded
    // immediately after each producer finishes instead of accumulating for the
    // entire document.
    auto retain_requested_range = [&](std::vector<HighlightSpan>& candidates) {
        for (auto& span : candidates) {
            span.start_byte = std::max(span.start_byte, range_start_byte);
            span.end_byte = std::min(span.end_byte, range_end_byte);
        }
        candidates.erase(
            std::remove_if(candidates.begin(), candidates.end(),
                [](const HighlightSpan& span) {
                    return span.start_byte >= span.end_byte;
                }),
            candidates.end());
    };

    // Tree-sitter offsets and the TSH input are both UTF-16LE byte offsets.
    // Keep the original source as the single canonical buffer and convert only
    // individual AST slices when a rule explicitly needs UTF-8 text predicates.
    const bool tree_source_valid = tree && (total_bytes & 1u) == 0 &&
        (text_start != nullptr || total_bytes == 0);

    std::vector<HighlightSpan> micro_spans;
    // Do not reserve proportional to document length: a 300 MB source can
    // contain comparatively few syntax captures, and the old estimate eagerly
    // reserved hundreds of megabytes before the first rule had matched.
    micro_spans.reserve(std::min<size_t>(length_chars / 4, 4096));

    for (const auto& rule : grammar->regex_rules) {
        if (!rule.enabled) continue;
        if (rule.type == RegexRuleType::BLOCK && rule.onig_start && rule.onig_end) {
            matchBlockRecursive(rule, text_start, text_end, text_start, text_end, micro_spans,
                diagnostic_match_counts ? &diagnostic_match_counts[0] : nullptr);
            retain_requested_range(micro_spans);
            continue;
        }

        if (!rule.onig_start) continue;

        OnigRegion* region = onig_region_new();
        if (!region) continue;
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
                if (diagnostic_match_counts) ++diagnostic_match_counts[0];
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
                        capture.allow_overlap = rule.allow_overlap ? 1 : 0;
                        micro_spans.push_back(capture);
                    };

                    if (rule.group_style_map.empty() && rule.named_group_style_map.empty()) {
                        if (rule.has_style) append_capture(0, rule.style_id);
                    } else {
                        auto whole_match = rule.group_style_map.find(0);
                        if (whole_match != rule.group_style_map.end()) append_capture(0, whole_match->second);
                        for (int g = 1; g < region->num_regs; ++g) {
                            auto style_it = rule.group_style_map.find(g);
                            if (style_it != rule.group_style_map.end()) append_capture(g, style_it->second);
                        }
                        for (const auto& named : rule.named_group_style_map) {
                            std::vector<uint16_t> encoded_name(named.first.begin(), named.first.end());
                            int group = onig_name_to_backref_number(
                                rule.onig_start,
                                reinterpret_cast<const UChar*>(encoded_name.data()),
                                reinterpret_cast<const UChar*>(encoded_name.data() + encoded_name.size()),
                                region);
                            append_capture(group, named.second);
                        }
                    }
                }

                current = (region->end[0] <= region->beg[0]) ?
                    advanceOneUtf16CodePoint(text_start + region->beg[0], text_end) :
                    text_start + region->end[0];
            } else {
                break;
            }
        }
        onig_region_free(region, 1);
        retain_requested_range(micro_spans);
    }
    retain_requested_range(micro_spans);

    // Tag regex spans before the stateful tokenizer appends its own results.
    for (auto& span : micro_spans) {
        span.source = static_cast<uint8_t>(HighlightSource::ONIG_REGEX);
    }
    const size_t monarch_span_begin = micro_spans.size();

    // Monarch stateful tokenizer. It runs on the same UTF-16 buffer as
    // Oniguruma rules and emits the same span ABI, so the Android renderer does
    // not need a second token model.
    if (!grammar->monarch_states.empty()) {
        std::vector<std::string> state_stack{grammar->monarch_start_state};
        OnigRegion* monarch_region = onig_region_new();
        OnigRegion* monarch_case_region = onig_region_new();
        if (!monarch_region || !monarch_case_region) {
            if (monarch_region) onig_region_free(monarch_region, 1);
            if (monarch_case_region) onig_region_free(monarch_case_region, 1);
            return final_spans;
        }
        uint32_t position = 0;
        uint32_t zero_width_steps = 0;
        while (position < total_bytes) {
            const MonarchRule* matched_rule = nullptr;
            std::unordered_set<std::string> include_path;
            std::function<const MonarchRule*(const std::string&)> find_rule =
                [&](const std::string& state_name) -> const MonarchRule* {
                    if (!include_path.insert(state_name).second) return nullptr;
                    auto state = grammar->monarch_states.find(state_name);
                    if (state == grammar->monarch_states.end()) {
                        include_path.erase(state_name);
                        return nullptr;
                    }
                    for (const auto& candidate : state->second) {
                        if (!candidate.enabled) continue;
                        if (!candidate.include_state.empty()) {
                            if (const MonarchRule* included = find_rule(candidate.include_state)) {
                                include_path.erase(state_name);
                                return included;
                            }
                        }
                        if (!candidate.pattern) continue;
                        const int length = onig_match(
                            candidate.pattern,
                            text_start,
                            text_end,
                            text_start + position,
                            monarch_region,
                            ONIG_OPTION_NONE
                        );
                        if (length >= 0) {
                            include_path.erase(state_name);
                            return &candidate;
                        }
                    }
                    include_path.erase(state_name);
                    return nullptr;
                };

            const std::string active_state = state_stack.empty() ?
                grammar->monarch_start_state : state_stack.back();
            matched_rule = find_rule(active_state);
            if (!matched_rule) {
                const UChar* next = advanceOneUtf16CodePoint(text_start + position, text_end);
                const uint32_t end = static_cast<uint32_t>(next - text_start);
                if (grammar->monarch_has_default_style && end > position &&
                    end > range_start_byte && position < range_end_byte) {
                    HighlightSpan span;
                    span.start_byte = std::max(position, range_start_byte);
                    span.end_byte = std::min(end, range_end_byte);
                    span.style_id = grammar->monarch_default_style_id;
                    span.priority = 0;
                    span.declaration_order = 0;
                    micro_spans.push_back(span);
                }
                position = end;
                zero_width_steps = 0;
                continue;
            }

            if (diagnostic_match_counts) ++diagnostic_match_counts[1];
            const uint32_t match_start = monarch_region->beg[0] >= 0 ?
                static_cast<uint32_t>(monarch_region->beg[0]) : position;
            const uint32_t match_end = monarch_region->end[0] >= 0 ?
                static_cast<uint32_t>(monarch_region->end[0]) : position;
            uint16_t resolved_style_id = matched_rule->style_id;
            bool has_resolved_style = matched_rule->has_style;
            if (!matched_rule->cases.empty()) {
                const MonarchCase* default_case = nullptr;
                bool case_matched = false;
                for (const auto& case_entry : matched_rule->cases) {
                    if (case_entry.is_default) {
                        default_case = &case_entry;
                        continue;
                    }
                    if (!case_entry.matcher) continue;
                    const int case_length = onig_match(
                        case_entry.matcher,
                        text_start,
                        text_end,
                        text_start + match_start,
                        monarch_case_region,
                        ONIG_OPTION_NONE
                    );
                    if (case_length >= 0 &&
                        match_start + static_cast<uint32_t>(case_length) == match_end) {
                        resolved_style_id = case_entry.style_id;
                        has_resolved_style = true;
                        case_matched = true;
                        break;
                    }
                }
                if (!case_matched) {
                    if (default_case) {
                        resolved_style_id = default_case->style_id;
                        has_resolved_style = true;
                    } else {
                        has_resolved_style = false;
                    }
                }
            }
            if (has_resolved_style && match_end > match_start &&
                (match_start & 1u) == 0 && (match_end & 1u) == 0 &&
                match_end > range_start_byte && match_start < range_end_byte) {
                HighlightSpan span;
                span.start_byte = std::max(match_start, range_start_byte);
                span.end_byte = std::min(match_end, range_end_byte);
                span.style_id = resolved_style_id;
                span.priority = matched_rule->priority;
                span.declaration_order = matched_rule->declaration_order;
                micro_spans.push_back(span);
            }

            const std::vector<std::string> old_stack = state_stack;
            auto strip_at = [](std::string value) {
                if (!value.empty() && value.front() == '@') value.erase(value.begin());
                return value;
            };
            if (matched_rule->pop || matched_rule->next_state == "@pop") {
                if (state_stack.size() > 1) state_stack.pop_back();
            } else if (matched_rule->next_state == "@popall") {
                state_stack.assign(1, grammar->monarch_start_state);
            } else if (matched_rule->next_state == "@push") {
                state_stack.push_back(state_stack.back());
            } else if (!matched_rule->next_state.empty()) {
                state_stack.back() = strip_at(matched_rule->next_state);
            } else if (!matched_rule->push_state.empty()) {
                state_stack.push_back(strip_at(matched_rule->push_state));
            } else if (!matched_rule->switch_state.empty()) {
                state_stack.back() = strip_at(matched_rule->switch_state);
            }

            if (matched_rule->rematch && state_stack != old_stack) {
                if (++zero_width_steps > 64) {
                    const UChar* next = advanceOneUtf16CodePoint(text_start + position, text_end);
                    position = static_cast<uint32_t>(next - text_start);
                    zero_width_steps = 0;
                }
            } else if (match_end > position) {
                position = match_end;
                zero_width_steps = 0;
            } else {
                const UChar* next = advanceOneUtf16CodePoint(text_start + position, text_end);
                position = static_cast<uint32_t>(next - text_start);
                if (++zero_width_steps > 64) zero_width_steps = 0;
            }
        }
        onig_region_free(monarch_case_region, 1);
        onig_region_free(monarch_region, 1);
    }
    for (size_t i = monarch_span_begin; i < micro_spans.size(); ++i) {
        micro_spans[i].source = static_cast<uint8_t>(HighlightSource::MONARCH);
    }
    retain_requested_range(micro_spans);

    std::vector<HighlightSpan> macro_spans;
    if (tree && tree_source_valid && !grammar->ast_selector_rules.empty()) {
        // AST/query capture density is grammar-dependent. Start with a small
        // bounded capacity and grow only when real captures are produced.
        macro_spans.reserve(std::min<size_t>(length_chars / 6, 4096));
        TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));
        std::vector<const AstSelectorRule*> candidate_rules;
        candidate_rules.reserve(grammar->ast_wildcard_rules.size() + 16);

        while (true) {
            TSNode node = ts_tree_cursor_current_node(&cursor);
            TSSymbol sym = ts_node_symbol(node);
            TSFieldId fid = ts_tree_cursor_current_field_id(&cursor);

            TSNode parent = ts_node_parent(node);
            TSSymbol parent_sym = ts_node_is_null(parent) ? 0 : ts_node_symbol(parent);

            candidate_rules.clear();
            auto append_indexed_rules = [&](const std::vector<uint32_t>& indices) {
                for (uint32_t index : indices) {
                    if (index < grammar->ast_selector_rules.size()) {
                        candidate_rules.push_back(&grammar->ast_selector_rules[index]);
                    }
                }
            };
            append_indexed_rules(grammar->ast_wildcard_rules);
            auto node_rules = grammar->ast_rules_by_node_symbol.find(sym);
            if (node_rules != grammar->ast_rules_by_node_symbol.end()) {
                append_indexed_rules(node_rules->second);
            }
            if (!ts_node_is_null(parent)) {
                auto field_rules = grammar->ast_field_rules_by_parent_symbol.find(parent_sym);
                if (field_rules != grammar->ast_field_rules_by_parent_symbol.end()) {
                    append_indexed_rules(field_rules->second);
                }
            }

            for (const AstSelectorRule* ast_rule_ptr : candidate_rules) {
                const AstSelectorRule& ast_rule = *ast_rule_ptr;
                if (!ast_rule.enabled) continue;
                if (ast_rule.named >= 0 &&
                    ts_node_is_named(node) != (ast_rule.named != 0)) continue;
                if (ast_rule.missing >= 0 &&
                    ts_node_is_missing(node) != (ast_rule.missing != 0)) continue;
                if (ast_rule.extra >= 0 &&
                    ts_node_is_extra(node) != (ast_rule.extra != 0)) continue;
                if (ast_rule.error >= 0 &&
                    ts_node_is_error(node) != (ast_rule.error != 0)) continue;
                if (ast_rule.has_error >= 0 &&
                    ts_node_has_error(node) != (ast_rule.has_error != 0)) continue;
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
                if (ast_rule.ancestor_symbol != 0) {
                    bool ancestor_found = false;
                    TSNode ancestor = parent;
                    while (!ts_node_is_null(ancestor)) {
                        if (ts_node_symbol(ancestor) == ast_rule.ancestor_symbol) {
                            ancestor_found = true;
                            break;
                        }
                        ancestor = ts_node_parent(ancestor);
                    }
                    if (!ancestor_found) continue;
                }
                if (ast_rule.child_symbol != 0) {
                    bool child_found = false;
                    const uint32_t child_count = ts_node_child_count(node);
                    for (uint32_t child_index = 0; child_index < child_count; ++child_index) {
                        if (ts_node_symbol(ts_node_child(node, child_index)) == ast_rule.child_symbol) {
                            child_found = true;
                            break;
                        }
                    }
                    if (!child_found) continue;
                }

                if (ast_rule.has_text_equals || ast_rule.has_text_prefix ||
                    ast_rule.has_text_suffix || ast_rule.text_regex) {
                    const uint32_t text_start = ts_node_start_byte(node);
                    const uint32_t text_end = ts_node_end_byte(node);
                    if (text_start > text_end || text_end > total_bytes ||
                        (text_start & 1u) || (text_end & 1u)) continue;
                    std::string node_text_storage;
                    const UChar* node_source = input_chars
                        ? reinterpret_cast<const UChar*>(input_chars) + text_start
                        : &kEmptyInput;
                    if (!tshUtf16SliceToUtf8(
                            node_source, text_end - text_start, node_text_storage)) continue;
                    const std::string_view node_text(node_text_storage);
                    if (ast_rule.has_text_equals && node_text != ast_rule.text_equals) continue;
                    if (ast_rule.has_text_prefix &&
                        (node_text.size() < ast_rule.text_prefix.size() ||
                         node_text.substr(0, ast_rule.text_prefix.size()) != ast_rule.text_prefix)) continue;
                    if (ast_rule.has_text_suffix &&
                        (node_text.size() < ast_rule.text_suffix.size() ||
                         node_text.substr(node_text.size() - ast_rule.text_suffix.size()) != ast_rule.text_suffix)) continue;
                    if (ast_rule.text_regex) {
                        if ((text_start & 1u) || (text_end & 1u) ||
                            text_end > total_bytes || text_end < text_start) continue;
                        // Oniguruma receives UTF-16LE bytes; Tree-sitter node
                        // offsets are already byte offsets into this same buffer.
                        const UChar* regex_source = input_chars
                            ? reinterpret_cast<const UChar*>(input_chars) : &kEmptyInput;
                        const UChar* regex_begin = regex_source + text_start;
                        const UChar* regex_end = regex_source + text_end;
                        OnigRegion* regex_region = onig_region_new();
                        if (!regex_region) continue;
                        const int regex_result = onig_search(
                            ast_rule.text_regex, regex_begin, regex_end,
                            regex_begin, regex_end, regex_region, ONIG_OPTION_NONE);
                        onig_region_free(regex_region, 1);
                        if (regex_result < 0) continue;
                    }
                }

                if (ast_rule.child_index != UINT32_MAX ||
                    ast_rule.named_child_index != UINT32_MAX ||
                    ast_rule.first_child >= 0 || ast_rule.last_child >= 0 ||
                    ast_rule.previous_sibling_symbol != 0 || ast_rule.next_sibling_symbol != 0) {
                    if (ts_node_is_null(parent)) continue;
                    const uint32_t sibling_count = ts_node_child_count(parent);
                    uint32_t current_index = UINT32_MAX;
                    uint32_t current_named_index = UINT32_MAX;
                    uint32_t named_seen = 0;
                    for (uint32_t sibling_index = 0; sibling_index < sibling_count; ++sibling_index) {
                        TSNode sibling = ts_node_child(parent, sibling_index);
                        if (ts_node_eq(sibling, node)) {
                            current_index = sibling_index;
                            if (ts_node_is_named(sibling)) current_named_index = named_seen;
                            break;
                        }
                        if (ts_node_is_named(sibling)) ++named_seen;
                    }
                    if (current_index == UINT32_MAX) continue;
                    if (ast_rule.child_index != UINT32_MAX &&
                        current_index != ast_rule.child_index) continue;
                    if (ast_rule.named_child_index != UINT32_MAX &&
                        current_named_index != ast_rule.named_child_index) continue;
                    if (ast_rule.first_child >= 0 &&
                        (current_index == 0) != (ast_rule.first_child != 0)) continue;
                    if (ast_rule.last_child >= 0 &&
                        (current_index + 1 == sibling_count) != (ast_rule.last_child != 0)) continue;
                    if (ast_rule.previous_sibling_symbol != 0) {
                        if (current_index == 0 ||
                            ts_node_symbol(ts_node_child(parent, current_index - 1)) !=
                                ast_rule.previous_sibling_symbol) continue;
                    }
                    if (ast_rule.next_sibling_symbol != 0) {
                        if (current_index + 1 >= sibling_count ||
                            ts_node_symbol(ts_node_child(parent, current_index + 1)) !=
                                ast_rule.next_sibling_symbol) continue;
                    }
                }

                if (diagnostic_match_counts) ++diagnostic_match_counts[2];
                if (ast_rule.has_style) {
                    HighlightSpan span;
                    const uint32_t node_start = ts_node_start_byte(node);
                    const uint32_t node_end = ts_node_end_byte(node);
                    if (node_start > node_end || node_end > total_bytes ||
                        (node_start & 1u) || (node_end & 1u)) continue;
                    span.start_byte = node_start;
                    span.end_byte = node_end;
                    span.style_id = ast_rule.style_id;
                    span.flags = 0;
                    span.priority = ast_rule.priority;
                    span.declaration_order = ast_rule.declaration_order;
                    span.allow_overlap = ast_rule.allow_overlap ? 1 : 0;
                    if (span.end_byte > range_start_byte && span.start_byte < range_end_byte) {
                        span.start_byte = std::max(span.start_byte, range_start_byte);
                        span.end_byte = std::min(span.end_byte, range_end_byte);
                        macro_spans.push_back(span);
                    }
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

    // AST selector candidates are tagged before native query captures join the
    // same macro collection.
    for (auto& span : macro_spans) {
        span.source = static_cast<uint8_t>(HighlightSource::AST_SELECTOR);
    }
    const size_t native_query_span_begin = macro_spans.size();

    if (tree && tree_source_valid && !grammar->native_queries.empty()) {
        // Each .tsh query block has its own native TSQuery and capture namespace.
        // This preserves per-block priority/order and avoids concatenating query
        // sources into a synthetic aggregate program.
        for (const auto& native_query : grammar->native_queries) {
            if (!native_query.enabled || !native_query.query) continue;
            TSQueryCursor* q_cursor = ts_query_cursor_new();
            if (!q_cursor) continue;
            // Restrict Tree-sitter query execution to the requested UTF-16LE byte
            // window. The AST remains document-wide so node offsets stay absolute.
            ts_query_cursor_set_byte_range(q_cursor, range_start_byte, range_end_byte);
            ts_query_cursor_exec(q_cursor, native_query.query, ts_tree_root_node(tree));

            TSQueryMatch match;
            uint32_t match_order = 0;
            while (ts_query_cursor_next_match(q_cursor, &match)) {
                const uint32_t current_order = match_order++;
                auto pattern_metadata = native_query.pattern_metadata.find(match.pattern_index);
                if (pattern_metadata != native_query.pattern_metadata.end() &&
                    !pattern_metadata->second.enabled) {
                    continue;
                }
                if (!tshEvaluateNativePredicates(native_query, match, text_start, total_bytes))
                    continue;
                if (diagnostic_match_counts) ++diagnostic_match_counts[3];
                const int32_t pattern_priority =
                    pattern_metadata != native_query.pattern_metadata.end() &&
                    pattern_metadata->second.has_priority
                        ? pattern_metadata->second.priority : native_query.priority;
                const uint32_t pattern_order =
                    pattern_metadata != native_query.pattern_metadata.end()
                        ? pattern_metadata->second.declaration_order : 0;
                for (uint32_t capture_index = 0; capture_index < match.capture_count; ++capture_index) {
                    const TSQueryCapture& capture = match.captures[capture_index];
                    auto style_binding = native_query.capture_style_ids.find(capture.index);
                    if (style_binding == native_query.capture_style_ids.end()) continue;
                    HighlightSpan span;
                    const uint32_t capture_start = ts_node_start_byte(capture.node);
                    const uint32_t capture_end = ts_node_end_byte(capture.node);
                    if (capture_start > capture_end || capture_end > total_bytes ||
                        (capture_start & 1u) || (capture_end & 1u)) continue;
                    span.start_byte = capture_start;
                    span.end_byte = capture_end;
                    span.style_id = style_binding->second;
                    span.flags = 0;
                    span.priority = pattern_priority;
                    span.declaration_order = native_query.declaration_order + pattern_order + current_order + capture_index;
                    macro_spans.push_back(span);
                }
            }
            ts_query_cursor_delete(q_cursor);
        }
    }
    for (size_t i = native_query_span_begin; i < macro_spans.size(); ++i) {
        macro_spans[i].source = static_cast<uint8_t>(HighlightSource::NATIVE_QUERY);
    }
    retain_requested_range(macro_spans);

    // Keep the public range API's output bounded to the requested document
    // window. Regex and Monarch still evaluate from document start to preserve
    // multiline matches and stateful tokenizer state; native Tree-sitter queries
    // are range-restricted above and do not enumerate unrelated captures.
    auto clip_to_requested_range = [&](std::vector<HighlightSpan>& candidates) {
        for (auto& span : candidates) {
            span.start_byte = std::max(span.start_byte, range_start_byte);
            span.end_byte = std::min(span.end_byte, range_end_byte);
        }
        candidates.erase(
            std::remove_if(candidates.begin(), candidates.end(),
                [](const HighlightSpan& span) {
                    return span.start_byte >= span.end_byte;
                }),
            candidates.end());
    };
    clip_to_requested_range(micro_spans);
    clip_to_requested_range(macro_spans);

    // Preserve producer candidates before overlap resolution, duplicate collapse,
    // clipping and adjacent-fragment merging. These are the actual captures
    // emitted by each pipeline, not merely the surviving renderer spans.
    if (diagnostic_spans) {
        diagnostic_spans->clear();
        diagnostic_spans->reserve(macro_spans.size() + micro_spans.size());
        diagnostic_spans->insert(diagnostic_spans->end(), macro_spans.begin(), macro_spans.end());
        diagnostic_spans->insert(diagnostic_spans->end(), micro_spans.begin(), micro_spans.end());
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

    // Resolve partial overlaps using explicit rule priority. Nested regions are
    // retained automatically; unrelated partial overlaps require @overlap: allow.
    // Discard malformed/empty ranges before conflict analysis. All coordinates
    // are UTF-16 byte offsets, so a valid span must be non-empty and even-aligned.
    final_spans.erase(std::remove_if(final_spans.begin(), final_spans.end(),
        [](const HighlightSpan& span) {
            return span.start_byte >= span.end_byte ||
                   (span.start_byte & 1u) != 0 || (span.end_byte & 1u) != 0;
        }), final_spans.end());

    // Establish a total, reproducible precedence order. The final tie-breakers
    // deliberately use range and style identity rather than unordered-map
    // iteration/insertion order from capture-group maps.
    std::stable_sort(final_spans.begin(), final_spans.end(),
        [](const HighlightSpan& a, const HighlightSpan& b) {
            if (a.priority != b.priority) return a.priority > b.priority;
            if (a.declaration_order != b.declaration_order)
                return a.declaration_order < b.declaration_order;
            if (a.start_byte != b.start_byte) return a.start_byte < b.start_byte;
            if (a.end_byte != b.end_byte) return a.end_byte > b.end_byte;
            if (a.source != b.source) return a.source < b.source;
            if (a.style_id != b.style_id) return a.style_id < b.style_id;
            if (a.allow_overlap != b.allow_overlap) return a.allow_overlap < b.allow_overlap;
            return a.flags < b.flags;
        });

    // Collapse exact duplicate emissions (for example, the same whole-match
    // capture declared through both group 0 and a named alias). Distinct styles
    // and distinct priorities are never merged.
    final_spans.erase(std::unique(final_spans.begin(), final_spans.end(),
        [](const HighlightSpan& a, const HighlightSpan& b) {
            return a.start_byte == b.start_byte && a.end_byte == b.end_byte &&
                   a.style_id == b.style_id && a.priority == b.priority &&
                   a.declaration_order == b.declaration_order &&
                   a.allow_overlap == b.allow_overlap && a.source == b.source &&
                   a.flags == b.flags;
        }), final_spans.end());

    // Resolve strict partial intersections by subtracting only the conflicting
    // interval. This preserves the non-conflicting portions of lower-priority
    // candidates instead of dropping their entire highlighting range.
    std::vector<HighlightSpan> resolved_spans;
    resolved_spans.reserve(final_spans.size());
    for (const auto& candidate : final_spans) {
        std::vector<HighlightSpan> fragments{candidate};
        for (const auto& accepted : resolved_spans) {
            if (fragments.empty()) break;
            std::vector<HighlightSpan> remaining;
            remaining.reserve(fragments.size() + 1);
            for (const auto& fragment : fragments) {
                if (fragment.end_byte <= accepted.start_byte ||
                    fragment.start_byte >= accepted.end_byte) {
                    remaining.push_back(fragment);
                    continue;
                }

                const bool same_range = fragment.start_byte == accepted.start_byte &&
                                        fragment.end_byte == accepted.end_byte;
                const bool fragment_contains =
                    fragment.start_byte <= accepted.start_byte &&
                    fragment.end_byte >= accepted.end_byte;
                const bool accepted_contains =
                    accepted.start_byte <= fragment.start_byte &&
                    accepted.end_byte >= fragment.end_byte;
                const bool nested = !same_range &&
                    (fragment_contains || accepted_contains);

                // Preserve explicit overlap and proper nesting. Only crossing
                // intervals are exclusive; the already accepted candidate wins.
                if (nested || fragment.allow_overlap || accepted.allow_overlap) {
                    remaining.push_back(fragment);
                    continue;
                }

                const uint32_t overlap_start =
                    std::max(fragment.start_byte, accepted.start_byte);
                const uint32_t overlap_end =
                    std::min(fragment.end_byte, accepted.end_byte);
                if (fragment.start_byte < overlap_start) {
                    HighlightSpan left = fragment;
                    left.end_byte = overlap_start;
                    remaining.push_back(left);
                }
                if (overlap_end < fragment.end_byte) {
                    HighlightSpan right = fragment;
                    right.start_byte = overlap_end;
                    remaining.push_back(right);
                }
            }
            fragments = std::move(remaining);
        }
        resolved_spans.insert(
            resolved_spans.end(), fragments.begin(), fragments.end());
    }

    // Rejoin adjacent fragments when every semantic attribute is identical.
    std::stable_sort(resolved_spans.begin(), resolved_spans.end(),
        [](const HighlightSpan& a, const HighlightSpan& b) {
            if (a.start_byte != b.start_byte) return a.start_byte < b.start_byte;
            if (a.end_byte != b.end_byte) return a.end_byte < b.end_byte;
            if (a.priority != b.priority) return a.priority > b.priority;
            if (a.declaration_order != b.declaration_order)
                return a.declaration_order < b.declaration_order;
            if (a.source != b.source) return a.source < b.source;
            if (a.style_id != b.style_id) return a.style_id < b.style_id;
            if (a.allow_overlap != b.allow_overlap)
                return a.allow_overlap < b.allow_overlap;
            return a.flags < b.flags;
        });
    std::vector<HighlightSpan> merged_spans;
    merged_spans.reserve(resolved_spans.size());
    for (const auto& span : resolved_spans) {
        if (!merged_spans.empty()) {
            auto& previous = merged_spans.back();
            const bool same_semantics =
                previous.end_byte == span.start_byte &&
                previous.style_id == span.style_id &&
                previous.flags == span.flags &&
                previous.priority == span.priority &&
                previous.declaration_order == span.declaration_order &&
                previous.allow_overlap == span.allow_overlap &&
                previous.source == span.source;
            if (same_semantics) {
                previous.end_byte = span.end_byte;
                continue;
            }
        }
        merged_spans.push_back(span);
    }
    final_spans = std::move(merged_spans);

    std::stable_sort(final_spans.begin(), final_spans.end(), [](const HighlightSpan& a, const HighlightSpan& b) {
        if (a.start_byte != b.start_byte) return a.start_byte < b.start_byte;
        // Apply outer captures first, then inner/high-priority captures.
        if (a.end_byte != b.end_byte) return a.end_byte > b.end_byte;
        if (a.priority != b.priority) return a.priority > b.priority;
        if (a.declaration_order != b.declaration_order)
            return a.declaration_order < b.declaration_order;
        if (a.source != b.source) return a.source < b.source;
        if (a.style_id != b.style_id) return a.style_id < b.style_id;
        return a.flags < b.flags;
    });

    return final_spans;
}

} // namespace windbell::tsh