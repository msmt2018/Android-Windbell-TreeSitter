/*
 * Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
 * 双流水线调度引擎与扫描线合成器完整实现
 */

#include "WindbellTshCore.h"
#include <algorithm>
#include <atomic>
#include <cstring>
#include <array>
#include <limits>
#include <functional>
#include <memory>
#include <iterator>
#include <unordered_set>
#include <string_view>
#include <stdexcept>
#include <new>
#include "ts__log.h"
#include "../largefile/TextSource.h"

namespace windbell::tsh {

namespace {

// ATS node names may resolve to Tree-sitter supertypes (for example,
// Java's "expression"). Grammar compilation stores the transitive subtype
// closure in reverse form so every runtime selector check is O(1) average.
static bool tshAstSymbolMatchesNode(
    const TshGrammar& grammar,
    TSNode node,
    TSSymbol selector_symbol
) noexcept {
    if (selector_symbol == 0 || ts_node_is_null(node)) return false;
    const TSSymbol visible_symbol = ts_node_symbol(node);
    const TSSymbol grammar_symbol = ts_node_grammar_symbol(node);
    if (selector_symbol == visible_symbol || selector_symbol == grammar_symbol) {
        return true;
    }
    const auto has_selector = [&](TSSymbol concrete_symbol) {
        const auto found =
            grammar.ast_supertype_selectors_by_node_symbol.find(concrete_symbol);
        return found != grammar.ast_supertype_selectors_by_node_symbol.end() &&
            std::binary_search(found->second.begin(), found->second.end(), selector_symbol);
    };
    return has_selector(visible_symbol) || has_selector(grammar_symbol);
}

static bool tshAstNodeMatchesSelector(
    const TshGrammar& grammar,
    TSNode node,
    TSSymbol selector_symbol,
    const std::string& selector_type
) noexcept {
    if (ts_node_is_null(node)) return false;
    if (tshAstSymbolMatchesNode(grammar, node, selector_symbol)) return true;

    if (!selector_type.empty()) {
        const char* visible_type = ts_node_type(node);
        const char* grammar_type = ts_node_grammar_type(node);
        if ((visible_type && selector_type == visible_type) ||
            (grammar_type && selector_type == grammar_type)) {
            return true;
        }
    }
    return false;
}

} // namespace

static void tshIncrementDiagnostic(uint32_t* value, uint64_t amount = 1u) noexcept {
    if (!value) return;
    const uint64_t remaining = static_cast<uint64_t>(UINT32_MAX - *value);
    *value = amount >= remaining
        ? (amount == 0 ? *value : UINT32_MAX)
        : static_cast<uint32_t>(*value + amount);
}

class HighlightSpanConflictIndex final {
public:
    void add(const HighlightSpan& span, size_t spanIndex,
             const std::vector<HighlightSpan>& spans) {
        if (span.allow_overlap) return;
        Node node;
        node.span_index = spanIndex;
        node.heap_priority = mix(static_cast<uint64_t>(spanIndex) + 0x9E3779B97F4A7C15ULL);
        node.max_end = span.end_byte;
        nodes_.push_back(node);
        root_ = insert(root_, nodes_.size() - 1u, spans);
    }

    void findOverlaps(uint32_t start, uint32_t end,
                      const std::vector<HighlightSpan>& spans,
                      std::vector<size_t>& output) const {
        output.clear();
        if (start >= end) return;
        collect(root_, start, end, spans, output);
    }

private:
    static constexpr size_t kNone = std::numeric_limits<size_t>::max();

    struct Node {
        size_t span_index = 0;
        size_t left = kNone;
        size_t right = kNone;
        uint64_t heap_priority = 0;
        uint32_t max_end = 0;
    };

    static uint64_t mix(uint64_t value) noexcept {
        value += 0x9E3779B97F4A7C15ULL;
        value = (value ^ (value >> 30u)) * 0xBF58476D1CE4E5B9ULL;
        value = (value ^ (value >> 27u)) * 0x94D049BB133111EBULL;
        return value ^ (value >> 31u);
    }

    void update(size_t index, const std::vector<HighlightSpan>& spans) noexcept {
        Node& node = nodes_[index];
        node.max_end = spans[node.span_index].end_byte;
        if (node.left != kNone)
            node.max_end = std::max(node.max_end, nodes_[node.left].max_end);
        if (node.right != kNone)
            node.max_end = std::max(node.max_end, nodes_[node.right].max_end);
    }

    bool lessKey(size_t left, size_t right,
                 const std::vector<HighlightSpan>& spans) const noexcept {
        const auto& a = spans[nodes_[left].span_index];
        const auto& b = spans[nodes_[right].span_index];
        return a.start_byte < b.start_byte ||
            (a.start_byte == b.start_byte &&
             nodes_[left].span_index < nodes_[right].span_index);
    }

    size_t rotateRight(size_t root, const std::vector<HighlightSpan>& spans) noexcept {
        const size_t pivot = nodes_[root].left;
        nodes_[root].left = nodes_[pivot].right;
        nodes_[pivot].right = root;
        update(root, spans);
        update(pivot, spans);
        return pivot;
    }

    size_t rotateLeft(size_t root, const std::vector<HighlightSpan>& spans) noexcept {
        const size_t pivot = nodes_[root].right;
        nodes_[root].right = nodes_[pivot].left;
        nodes_[pivot].left = root;
        update(root, spans);
        update(pivot, spans);
        return pivot;
    }

    size_t insert(size_t root, size_t inserted,
                  const std::vector<HighlightSpan>& spans) {
        if (root == kNone) return inserted;
        if (lessKey(inserted, root, spans)) {
            nodes_[root].left = insert(nodes_[root].left, inserted, spans);
            if (nodes_[nodes_[root].left].heap_priority >
                nodes_[root].heap_priority) root = rotateRight(root, spans);
        } else {
            nodes_[root].right = insert(nodes_[root].right, inserted, spans);
            if (nodes_[nodes_[root].right].heap_priority >
                nodes_[root].heap_priority) root = rotateLeft(root, spans);
        }
        update(root, spans);
        return root;
    }

    void collect(size_t index, uint32_t start, uint32_t end,
                 const std::vector<HighlightSpan>& spans,
                 std::vector<size_t>& output) const {
        if (index == kNone) return;
        const Node& node = nodes_[index];
        // A subtree whose maximum end is before the query start cannot overlap.
        if (node.max_end <= start) return;
        if (node.left != kNone && nodes_[node.left].max_end > start)
            collect(node.left, start, end, spans, output);

        const HighlightSpan& accepted = spans[node.span_index];
        if (accepted.start_byte < end && accepted.end_byte > start)
            output.push_back(node.span_index);

        // The tree is ordered by start byte; no right-side interval can overlap
        // once this node starts at/after the query end.
        if (accepted.start_byte < end)
            collect(node.right, start, end, spans, output);
    }

    std::vector<Node> nodes_;
    size_t root_ = kNone;
};

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

// Compare literal AST text predicates directly against a random-access
// UTF-16LE source. This avoids allocating a node-sized UTF-16 buffer and a
// second UTF-8 copy for common equals/prefix/suffix selectors on large files.
static bool tshSourceMatchesUtf8Literal(
    const windbell::largefile::TextSource& source,
    uint64_t start_byte,
    uint64_t end_byte,
    const std::string& expected,
    uint8_t mode // 0=equals, 1=prefix, 2=suffix
) {
    if (start_byte > end_byte || end_byte > source.sizeBytes() ||
        ((start_byte | end_byte) & 1u)) {
        throw std::runtime_error("TSH AST literal predicate received an invalid TextSource range");
    }
    if (mode == 0 && end_byte - start_byte > expected.size() * 2ULL) return false;
    if (mode == 1 && start_byte == end_byte) return expected.empty();
    if (mode == 2 && start_byte == end_byte) return expected.empty();
    if (expected.empty()) return mode != 0 || start_byte == end_byte;

    std::string tail;
    if (mode == 2) tail.reserve(expected.size());
    size_t output_index = 0;
    uint16_t pending_high = 0;
    bool prefix_complete = false;
    auto emit = [&](uint32_t cp) -> bool {
        char encoded[4];
        size_t count = 0;
        if (cp <= 0x7F) encoded[count++] = static_cast<char>(cp);
        else if (cp <= 0x7FF) {
            encoded[count++] = static_cast<char>(0xC0 | (cp >> 6));
            encoded[count++] = static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp <= 0xFFFF) {
            encoded[count++] = static_cast<char>(0xE0 | (cp >> 12));
            encoded[count++] = static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            encoded[count++] = static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            encoded[count++] = static_cast<char>(0xF0 | (cp >> 18));
            encoded[count++] = static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            encoded[count++] = static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            encoded[count++] = static_cast<char>(0x80 | (cp & 0x3F));
        }
        if (mode == 1 && prefix_complete) return true;
        for (size_t i = 0; i < count; ++i) {
            if (mode == 0 || mode == 1) {
                if (output_index >= expected.size() || encoded[i] != expected[output_index])
                    return false;
                ++output_index;
                if (mode == 1 && output_index == expected.size()) prefix_complete = true;
            } else {
                tail.push_back(encoded[i]);
                if (tail.size() > expected.size()) tail.erase(tail.begin());
            }
        }
        return true;
    };

    uint64_t offset = start_byte;
    while (offset < end_byte) {
        windbell::largefile::TextChunkView chunk{};
        const uint32_t request = static_cast<uint32_t>(std::min<uint64_t>(
            windbell::largefile::kTreeSitterInputChunkBytes, end_byte - offset));
        if (!source.read(offset, request, &chunk) || !chunk.data || chunk.size == 0 ||
            (chunk.size & 1u) || chunk.size > end_byte - offset) {
            throw std::runtime_error("TSH AST literal predicate could not read a valid TextSource chunk");
        }
        for (uint32_t i = 0; i < chunk.size; i += 2) {
            const auto* bytes = reinterpret_cast<const uint8_t*>(chunk.data);
            const uint16_t unit = static_cast<uint16_t>(bytes[i]) |
                (static_cast<uint16_t>(bytes[i + 1]) << 8);
            if (pending_high) {
                if (unit < 0xDC00 || unit > 0xDFFF) {
                    throw std::runtime_error("TSH AST literal predicate encountered malformed UTF-16");
                }
                const uint32_t cp = 0x10000u +
                    ((static_cast<uint32_t>(pending_high) - 0xD800u) << 10) +
                    (static_cast<uint32_t>(unit) - 0xDC00u);
                pending_high = 0;
                if (!emit(cp)) return false;
            } else if (unit >= 0xD800 && unit <= 0xDBFF) {
                pending_high = unit;
            } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
                throw std::runtime_error("TSH AST literal predicate encountered an unpaired UTF-16 low surrogate");
            } else if (!emit(unit)) return false;
            if (mode == 1 && prefix_complete) return true;
        }
        offset += chunk.size;
    }
    if (pending_high) {
        throw std::runtime_error("TSH AST literal predicate encountered an unpaired UTF-16 high surrogate");
    }
    if (mode == 0) return output_index == expected.size();
    if (mode == 1) return prefix_complete;
    return tail == expected;
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

static int tshPredicateRegexMatchesContiguous(
    regex_t* regex, const UChar* begin, const UChar* end
);

static bool tshSourceRegexMatchesBounded(
    const windbell::largefile::TextSource& source,
    uint64_t start_byte,
    uint64_t end_byte,
    regex_t* regex,
    uint64_t maximum_match_bytes
) {
    if (!regex || start_byte > end_byte || end_byte > source.sizeBytes() ||
        (start_byte & 1u) || (end_byte & 1u) || maximum_match_bytes == 0) {
        throw std::runtime_error("TSH bounded AST Regex received an invalid TextSource range");
    }
    static const UChar empty_input = 0;
    if (start_byte == end_byte) {
        const int matched = tshPredicateRegexMatchesContiguous(
            regex, &empty_input, &empty_input);
        if (matched < 0) {
            throw std::runtime_error("TSH bounded AST Regex failed on an empty input");
        }
        return matched == 1;
    }
    constexpr uint64_t kCoreWindowBytes = 256u * 1024u;
    const uint64_t overlap = maximum_match_bytes >= 2u
        ? maximum_match_bytes - 2u : 0u;
    // Reuse the bounded input and match region across windows. AST text
    // predicates can be evaluated for many nodes during one viewport pass;
    // per-window allocations otherwise amplify latency on large files.
    std::vector<UChar> window;
    struct RegionOwner {
        OnigRegion* value;
        ~RegionOwner() { if (value) onig_region_free(value, 1); }
    } region{onig_region_new()};
    if (!region.value) throw std::bad_alloc();

    uint64_t core_start = start_byte;
    while (core_start < end_byte) {
        const uint64_t remaining = end_byte - core_start;
        const uint64_t core_end = remaining <= kCoreWindowBytes
            ? end_byte : core_start + kCoreWindowBytes;
        const uint64_t after_core = end_byte - core_end;
        const uint64_t window_end = overlap >= after_core
            ? end_byte : core_end + overlap;
        const uint64_t window_bytes = window_end - core_start;
        if (window_bytes == 0 || (window_bytes & 1u) != 0 ||
            window_bytes > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
            throw std::runtime_error("TSH bounded AST Regex window has an invalid size");
        }
        try { window.resize(static_cast<size_t>(window_bytes)); }
        catch (const std::bad_alloc&) { throw; }
        if (!windbell::largefile::copyTextRange(
                source, core_start, window_bytes, window.data())) {
            throw std::runtime_error("TSH bounded AST Regex could not read its TextSource window");
        }
        const UChar* begin = window.data();
        const UChar* end = begin + window.size();
        const UChar* current = begin;
        while (current < end) {
            const int hit = onig_search(
                regex, begin, end, current, end, region.value, ONIG_OPTION_NONE);
            if (hit == ONIG_MISMATCH) break;
            if (hit < 0) {
                throw std::runtime_error(
                    "Oniguruma bounded AST Regex execution failed with status " +
                    std::to_string(hit));
            }
            if (region.value->beg[0] < 0 ||
                region.value->end[0] < region.value->beg[0]) {
                throw std::runtime_error(
                    "Oniguruma bounded AST Regex returned an invalid match range");
            }
            const uint64_t local_start = static_cast<uint64_t>(region.value->beg[0]);
            if (local_start < core_end - core_start) return true;
            break;
        }
        core_start = core_end;
    }
    return false;
}

static bool tshPredicateCaptureTexts(const TSQueryMatch& match, uint32_t capture_id,
    const UChar* utf16_source,
    const windbell::largefile::TextSource* text_source,
    uint32_t source_bytes,
    std::vector<std::vector<UChar>>& texts) {
    texts.clear();
    // This path is used only when a regex cannot use the bounded-window
    // TextSource executor. Never materialize an arbitrarily large capture:
    // fail explicitly instead of allowing a document-sized temporary buffer.
    constexpr uint64_t kMaxMaterializedCaptureBytes = 8u * 1024u * 1024u;
    uint64_t materialized_bytes = 0;
    try {
    for (uint32_t i = 0; i < match.capture_count; ++i) {
        if (match.captures[i].index != capture_id) continue;
        const uint32_t start = ts_node_start_byte(match.captures[i].node);
        const uint32_t end = ts_node_end_byte(match.captures[i].node);
        if (start > end || end > source_bytes || (start & 1u) || (end & 1u) ||
            (!utf16_source && !text_source && end != start)) return false;
        const uint64_t capture_bytes = static_cast<uint64_t>(end) - start;
        if (capture_bytes > kMaxMaterializedCaptureBytes - materialized_bytes)
            return false;
        materialized_bytes += capture_bytes;
        std::vector<UChar> converted;
        if (end > start) {
            converted.resize((end - start) / sizeof(UChar));
            if (text_source) {
                if (!windbell::largefile::copyTextRange(*text_source, start, end - start, converted.data())) return false;
            } else if (utf16_source) {
                std::memcpy(converted.data(), utf16_source + start, end - start);
            } else return false;
        }
        texts.push_back(std::move(converted));
    }
    // A query may legitimately omit an optional capture. Negative predicates
    // still evaluate successfully for an absent capture, matching Tree-sitter's
    // predicate semantics; the operator decides the empty-set result.
    return true;
    } catch (const std::bad_alloc&) {
        texts.clear();
        return false;
    }
}

static int tshPredicateCaptureEquals(const TSNode& node,
    const std::vector<UChar>& expected,
    const UChar* utf16_source,
    const windbell::largefile::TextSource* text_source,
    uint32_t source_bytes) {
    const uint32_t start = ts_node_start_byte(node);
    const uint32_t end = ts_node_end_byte(node);
    if (start > end || end > source_bytes || (start & 1u) || (end & 1u)) return -1;
    const uint64_t length = static_cast<uint64_t>(end) - start;
    if (length != expected.size()) return 0;
    if (length == 0) return 1;
    if (utf16_source) {
        return std::memcmp(utf16_source + start, expected.data(),
            static_cast<size_t>(length)) == 0 ? 1 : 0;
    }
    if (!text_source) return -1;

    // Compare large captures incrementally. Predicate equality must not
    // materialize a potentially document-sized capture into a temporary vector.
    constexpr uint32_t kCompareChunkBytes = 16u * 1024u;
    std::array<UChar, kCompareChunkBytes> scratch{};
    uint64_t compared = 0;
    while (compared < length) {
        const uint32_t amount = static_cast<uint32_t>(
            std::min<uint64_t>(kCompareChunkBytes, length - compared));
        if (!windbell::largefile::copyTextRange(
                *text_source, static_cast<uint64_t>(start) + compared,
                amount, scratch.data())) return -1;
        if (std::memcmp(scratch.data(), expected.data() + compared, amount) != 0)
            return 0;
        compared += amount;
    }
    return 1;
}

static int tshPredicateNodesEqual(const TSNode& left, const TSNode& right,
    const UChar* utf16_source,
    const windbell::largefile::TextSource* text_source,
    uint32_t source_bytes) {
    const uint32_t left_start = ts_node_start_byte(left);
    const uint32_t left_end = ts_node_end_byte(left);
    const uint32_t right_start = ts_node_start_byte(right);
    const uint32_t right_end = ts_node_end_byte(right);
    if (left_start > left_end || right_start > right_end ||
        left_end > source_bytes || right_end > source_bytes ||
        (left_start & 1u) || (left_end & 1u) ||
        (right_start & 1u) || (right_end & 1u)) return -1;
    const uint64_t length = static_cast<uint64_t>(left_end) - left_start;
    if (length != static_cast<uint64_t>(right_end) - right_start) return 0;
    if (length == 0) return 1;
    if (utf16_source) {
        return std::memcmp(utf16_source + left_start, utf16_source + right_start,
            static_cast<size_t>(length)) == 0 ? 1 : 0;
    }
    if (!text_source) return -1;
    constexpr uint32_t kCompareChunkBytes = 16u * 1024u;
    std::array<UChar, kCompareChunkBytes> left_buffer{};
    std::array<UChar, kCompareChunkBytes> right_buffer{};
    uint64_t compared = 0;
    while (compared < length) {
        const uint32_t amount = static_cast<uint32_t>(
            std::min<uint64_t>(kCompareChunkBytes, length - compared));
        if (!windbell::largefile::copyTextRange(*text_source,
                static_cast<uint64_t>(left_start) + compared, amount, left_buffer.data()) ||
            !windbell::largefile::copyTextRange(*text_source,
                static_cast<uint64_t>(right_start) + compared, amount, right_buffer.data()))
            return -1;
        if (std::memcmp(left_buffer.data(), right_buffer.data(), amount) != 0) return 0;
        compared += amount;
    }
    return 1;
}

static int tshPredicateContainsText(
    const UChar* utf16_source,
    const windbell::largefile::TextSource* text_source,
    uint32_t source_bytes,
    uint32_t start_byte,
    uint32_t end_byte,
    const std::vector<UChar>& needle) {
    if (start_byte > end_byte || end_byte > source_bytes ||
        (start_byte & 1u) || (end_byte & 1u)) return -1;
    if (needle.empty()) return 1;
    const uint64_t text_bytes = static_cast<uint64_t>(end_byte) - start_byte;
    if (needle.size() > text_bytes) return 0;

    // Oniguruma UChar is an octet. Match the UTF-16LE byte sequence while
    // accepting matches only at UTF-16 code-unit boundaries.
    std::vector<size_t> prefix;
    try {
        prefix.resize(needle.size());
    } catch (const std::bad_alloc&) {
        return -1;
    }
    for (size_t i = 1, matched = 0; i < needle.size(); ++i) {
        while (matched > 0 && needle[i] != needle[matched])
            matched = prefix[matched - 1];
        if (needle[i] == needle[matched]) ++matched;
        prefix[i] = matched;
    }

    constexpr uint32_t kChunkBytes = 16u * 1024u;
    std::array<UChar, kChunkBytes> chunk{};
    uint64_t consumed_bytes = 0;
    size_t matched = 0;
    while (consumed_bytes < text_bytes) {
        const uint32_t amount_bytes = static_cast<uint32_t>(
            std::min<uint64_t>(kChunkBytes, text_bytes - consumed_bytes));
        UChar* input = chunk.data();
        if (text_source) {
            if (!windbell::largefile::copyTextRange(*text_source,
                    static_cast<uint64_t>(start_byte) + consumed_bytes,
                    amount_bytes, input)) return -1;
        } else if (utf16_source) {
            std::memcpy(input, utf16_source + start_byte + consumed_bytes, amount_bytes);
        } else {
            return -1;
        }
        for (uint32_t i = 0; i < amount_bytes; ++i) {
            while (matched > 0 && input[i] != needle[matched])
                matched = prefix[matched - 1];
            if (input[i] == needle[matched]) ++matched;
            if (matched == needle.size()) {
                const uint64_t match_start = consumed_bytes + i + 1u - needle.size();
                if ((match_start & 1u) == 0) return 1;
                matched = prefix[matched - 1];
            }
        }
        consumed_bytes += amount_bytes;
    }
    return 0;
}

static bool tshPredicateNodeHasType(const TSNode& node,
    const std::vector<std::string>& expected_types) {
    const char* type = ts_node_type(node);
    if (!type) return false;
    for (const std::string& expected : expected_types) {
        if (expected == type) return true;
    }
    return false;
}

static int tshPredicateRegexMatchesContiguous(
    regex_t* regex, const UChar* begin, const UChar* end
) {
    if (!regex) return -1;
    static const UChar empty_input = 0;
    if (!begin && !end) begin = end = &empty_input;
    else if (!begin || !end || end < begin) return -1;
    if (begin == end) begin = end = &empty_input;
    OnigRegion* region = onig_region_new();
    if (!region) return -1;
    const int result = onig_search(regex, begin, end, begin, end, region, ONIG_OPTION_NONE);
    onig_region_free(region, 1);
    if (result >= 0) return 1;
    return result == ONIG_MISMATCH ? 0 : -1;
}

static int tshPredicateRegexMatchesTextSourceBounded(
    const windbell::largefile::TextSource& source,
    uint64_t start_byte,
    uint64_t end_byte,
    regex_t* regex,
    uint64_t maximum_match_bytes
) {
    if (!regex || start_byte > end_byte || (start_byte & 1u) ||
        (end_byte & 1u) || end_byte > source.sizeBytes() ||
        maximum_match_bytes == 0 || maximum_match_bytes > 8u * 1024u * 1024u)
        return -1;
    if (start_byte == end_byte) {
        static const UChar empty_input = 0;
        return tshPredicateRegexMatchesContiguous(regex, &empty_input, &empty_input);
    }
    constexpr uint64_t kCoreWindowBytes = 256u * 1024u;
    const uint64_t overlap = maximum_match_bytes >= 2u
        ? maximum_match_bytes - 2u : 0u;
    // A query predicate can be evaluated for many captures in a dense
    // document. Keep one bounded scratch window and one Oniguruma region for
    // this capture evaluation instead of allocating both for every partition.
    std::vector<UChar> window;
    struct RegionOwner {
        OnigRegion* value;
        ~RegionOwner() { if (value) onig_region_free(value, 1); }
    } region{onig_region_new()};
    if (!region.value) return -1;

    uint64_t core_start = start_byte;
    while (core_start < end_byte) {
        const uint64_t remaining = end_byte - core_start;
        const uint64_t core_end = remaining <= kCoreWindowBytes
            ? end_byte : core_start + kCoreWindowBytes;
        const uint64_t after_core = end_byte - core_end;
        const uint64_t window_end = overlap >= after_core
            ? end_byte : core_end + overlap;
        const uint64_t window_bytes = window_end - core_start;
        if (window_bytes == 0 || (window_bytes & 1u) != 0 ||
            window_bytes > static_cast<uint64_t>(std::numeric_limits<size_t>::max()))
            return -1;
        try { window.resize(static_cast<size_t>(window_bytes)); }
        catch (const std::bad_alloc&) { return -1; }
        if (!windbell::largefile::copyTextRange(
                source, core_start, window_bytes, window.data())) return -1;
        const UChar* begin = window.data();
        const UChar* end = begin + window.size();
        const int hit = onig_search(
            regex, begin, end, begin, end, region.value, ONIG_OPTION_NONE);
        bool found_in_core = false;
        bool failed = false;
        if (hit >= 0) {
            if (region.value->beg[0] < 0 ||
                region.value->end[0] < region.value->beg[0]) failed = true;
            else found_in_core =
                static_cast<uint64_t>(region.value->beg[0]) < core_end - core_start;
        } else if (hit != ONIG_MISMATCH) {
            failed = true;
        }
        if (failed) return -1;
        if (found_in_core) return 1;
        core_start = core_end;
    }
    return 0;
}

enum class TshPredicateResult : uint8_t { ACCEPT, REJECT, ERROR };

static TshPredicateResult tshEvaluateNativePredicates(const NativeQueryProgram& program,
    const TSQueryMatch& match, const UChar* utf16_source,
    const windbell::largefile::TextSource* text_source, uint32_t source_bytes) {
    const TSQuery* query = program.query;
    if (match.pattern_index >= program.predicates_by_pattern.size()) return TshPredicateResult::REJECT;
    const auto& predicates = program.predicates_by_pattern[match.pattern_index];
    if (predicates.empty()) return TshPredicateResult::ACCEPT;
    uint32_t predicate_index = 0;
    for (const auto& predicate : predicates) {
        // Tree-sitter predicate metadata terminates each expression with a
        // Done step. Treat it as a delimiter, not as an operand; otherwise
        // every custom predicate is rejected before its evaluator runs.
        const size_t predicate_size = predicate.size() -
            (!predicate.empty() && predicate.back().type == TSQueryPredicateStepTypeDone ? 1u : 0u);
        if (predicate.empty() || predicate[0].type != TSQueryPredicateStepTypeString) return TshPredicateResult::REJECT;

        uint32_t op_length = 0;
        const char* op_text = ts_query_string_value_for_id(query, predicate[0].value_id, &op_length);
        if (!op_text || predicate_size < 3 ||
            predicate[1].type != TSQueryPredicateStepTypeCapture) return TshPredicateResult::REJECT;
        std::string op;
        try {
            op.assign(op_text, op_length);
        } catch (const std::bad_alloc&) {
            return TshPredicateResult::ERROR;
        }
        if (!op.empty() && op.front() == '#') op.erase(op.begin());

        std::vector<std::vector<UChar>> expected;
        std::vector<std::string> expected_types;
        std::vector<std::vector<UChar>> actual;
        const bool contains_operator = op == "contains?" || op == "not-contains?" ||
            op == "any-contains?" || op == "any-not-contains?";
        const bool ancestry_operator = op == "has-ancestor?" || op == "not-has-ancestor?" ||
            op == "any-has-ancestor?" || op == "any-not-has-ancestor?";
        const bool parent_operator = op == "has-parent?" || op == "not-has-parent?" ||
            op == "any-has-parent?" || op == "any-not-has-parent?";
        const bool kind_operator = op == "kind-eq?" || op == "not-kind-eq?" ||
            op == "any-kind-eq?" || op == "any-not-kind-eq?";
        const bool structural_operator = ancestry_operator || parent_operator || kind_operator;
        try {
            for (size_t i = 2; i < predicate_size; ++i) {
                if (predicate[i].type == TSQueryPredicateStepTypeCapture &&
                    (op == "eq?" || op == "not-eq?" ||
                     op == "any-eq?" || op == "any-not-eq?")) continue;
                if (predicate[i].type != TSQueryPredicateStepTypeString)
                    return TshPredicateResult::REJECT;
                uint32_t length = 0;
                const char* value = ts_query_string_value_for_id(query, predicate[i].value_id, &length);
                if (!value) return TshPredicateResult::REJECT;
                if (structural_operator) {
                    expected_types.emplace_back(value, length);
                } else {
                    std::vector<UChar> converted;
                    if (!tshUtf8ToUtf16(std::string(value, length), converted))
                        return TshPredicateResult::REJECT;
                    expected.push_back(std::move(converted));
                }
            }
        } catch (const std::bad_alloc&) {
            return TshPredicateResult::ERROR;
        }

        bool accepted = false;
        const bool equality_predicate = op == "eq?" || op == "not-eq?" ||
            op == "any-eq?" || op == "any-not-eq?";
        if (equality_predicate && predicate_size == 3 &&
            predicate[2].type == TSQueryPredicateStepTypeCapture) {
            const bool any = op == "any-eq?" || op == "any-not-eq?";
            const bool positive = op == "eq?" || op == "any-eq?";
            bool has_left = false;
            bool has_right = false;
            bool all_left_satisfied = true;
            bool any_pair_satisfied = false;
            for (uint32_t i = 0; i < match.capture_count; ++i) {
                if (match.captures[i].index != predicate[1].value_id) continue;
                has_left = true;
                bool left_satisfied = false;
                bool compared_any = false;
                for (uint32_t j = 0; j < match.capture_count; ++j) {
                    if (match.captures[j].index != predicate[2].value_id) continue;
                    has_right = true;
                    const int comparison = tshPredicateNodesEqual(
                        match.captures[i].node, match.captures[j].node,
                        utf16_source, text_source, source_bytes);
                    if (comparison < 0) return TshPredicateResult::ERROR;
                    compared_any = true;
                    if (comparison == (positive ? 1 : 0)) {
                        left_satisfied = true;
                        any_pair_satisfied = true;
                    }
                }
                if (!compared_any) all_left_satisfied = false;
                else all_left_satisfied = all_left_satisfied && left_satisfied;
            }
            // Match Tree-sitter's EqCapture contract:
            // - non-any predicates quantify over every left capture (vacuously true
            //   when the left capture is absent);
            // - any-* predicates require at least one left capture and one
            //   satisfying pair;
            // - a missing right capture cannot satisfy either equality polarity.
            if (any) {
                accepted = has_left && has_right && any_pair_satisfied;
            } else {
                accepted = !has_left || (has_right && all_left_satisfied);
            }
        } else if (equality_predicate) {
            if (expected.size() != 1) return TshPredicateResult::REJECT;
            const bool any = op == "any-eq?" || op == "any-not-eq?";
            const bool positive = op == "eq?" || op == "any-eq?";
            bool has_capture = false;
            bool all_satisfied = true;
            bool any_satisfied = false;
            for (uint32_t i = 0; i < match.capture_count; ++i) {
                if (match.captures[i].index != predicate[1].value_id) continue;
                has_capture = true;
                const int comparison = tshPredicateCaptureEquals(match.captures[i].node,
                    expected.front(), utf16_source, text_source, source_bytes);
                if (comparison < 0) return TshPredicateResult::ERROR;
                const bool satisfied = (comparison == 1) == positive;
                all_satisfied = all_satisfied && satisfied;
                any_satisfied = any_satisfied || satisfied;
            }
            // EqString's missing-capture result is determined by polarity,
            // even for any-* forms: eq? / any-eq? reject, while
            // not-eq? / any-not-eq? accept.
            accepted = !has_capture ? !positive : (any ? any_satisfied : all_satisfied);
            if (op == "any-not-eq?" || op == "any-eq?") {
                static std::atomic<uint32_t> missing_capture_debug_count{0};
                if (!has_capture && missing_capture_debug_count.fetch_add(1, std::memory_order_relaxed) < 16u) {
                    LOGD("WindbellTSH", "Native equality predicate: op=%s pattern=%u predicate=%u capture_id=%u match_captures=%u accepted=%d",
                        op.c_str(), match.pattern_index, predicate_index, predicate[1].value_id,
                        match.capture_count, accepted ? 1 : 0);
                    for (uint32_t capture = 0; capture < match.capture_count; ++capture) {
                        LOGD("WindbellTSH", "  capture[%u].index=%u", capture, match.captures[capture].index);
                    }
                }
            }
        } else if (op == "any-of?" || op == "not-any-of?") {
            if (expected.empty()) return TshPredicateResult::REJECT;
            bool all_in_set = true;
            bool any_in_set = false;
            for (uint32_t i = 0; i < match.capture_count; ++i) {
                if (match.captures[i].index != predicate[1].value_id) continue;
                bool found = false;
                for (const auto& candidate : expected) {
                    const int comparison = tshPredicateCaptureEquals(match.captures[i].node,
                        candidate, utf16_source, text_source, source_bytes);
                    if (comparison < 0) return TshPredicateResult::ERROR;
                    if (comparison == 1) {
                        found = true;
                        break;
                    }
                }
                all_in_set = all_in_set && found;
                any_in_set = any_in_set || found;
            }
            accepted = op == "any-of?" ? all_in_set : !any_in_set;
        } else if (contains_operator || structural_operator) {
            const bool any = op.rfind("any-", 0) == 0;
            const bool negative = op.find("not-") != std::string::npos;
            // Empty literals are valid substring predicates and match every capture.
            if (structural_operator && expected_types.empty()) return TshPredicateResult::REJECT;
            bool has_capture = false;
            bool all_satisfied = true;
            bool any_satisfied = false;
            for (uint32_t i = 0; i < match.capture_count; ++i) {
                if (match.captures[i].index != predicate[1].value_id) continue;
                has_capture = true;
                const TSNode& node = match.captures[i].node;
                bool matched = false;
                if (contains_operator) {
                    for (const auto& literal : expected) {
                        const int result = tshPredicateContainsText(
                            utf16_source, text_source, source_bytes,
                            ts_node_start_byte(node), ts_node_end_byte(node), literal);
                        if (result < 0) return TshPredicateResult::ERROR;
                        if (result == 1) { matched = true; break; }
                    }
                } else if (kind_operator) {
                    matched = tshPredicateNodeHasType(node, expected_types);
                } else if (parent_operator) {
                    const TSNode parent = ts_node_parent(node);
                    matched = !ts_node_is_null(parent) &&
                        tshPredicateNodeHasType(parent, expected_types);
                } else {
                    TSNode ancestor = ts_node_parent(node);
                    while (!ts_node_is_null(ancestor)) {
                        if (tshPredicateNodeHasType(ancestor, expected_types)) {
                            matched = true;
                            break;
                        }
                        ancestor = ts_node_parent(ancestor);
                    }
                }
                const bool satisfied = negative ? !matched : matched;
                all_satisfied = all_satisfied && satisfied;
                any_satisfied = any_satisfied || satisfied;
                if (any && any_satisfied) break;
                if (!any && !all_satisfied) break;
            }
            // Match the original query-filter fallback: a predicate whose
            // target capture is absent is vacuously true, including any-* forms.
            // Once captures exist, ordinary forms require all and any-* requires
            // at least one capture to satisfy the node-level predicate.
            accepted = !has_capture || (any ? any_satisfied : all_satisfied);
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
            if (!compiled || !compiled->regex) return TshPredicateResult::REJECT;

            bool has_capture = false;
            bool all_match = true;
            bool all_not_match = true;
            // File-backed TextSource implementations may already expose a stable
            // contiguous mapping. Use it directly before considering window copies
            // or the bounded fallback materializer; this removes the 8 MiB temporary
            // capture ceiling for mmap/contiguous sources without changing regex
            // semantics or allocating another document-sized buffer.
            const char* contiguous_source_data = nullptr;
            uint64_t contiguous_source_bytes = 0;
            const bool has_contiguous_source = text_source &&
                text_source->contiguousData(&contiguous_source_data, &contiguous_source_bytes) &&
                contiguous_source_bytes >= source_bytes &&
                (contiguous_source_data != nullptr || source_bytes == 0);
            if (has_contiguous_source) {
                for (uint32_t i = 0; i < match.capture_count; ++i) {
                    if (match.captures[i].index != predicate[1].value_id) continue;
                    has_capture = true;
                    const uint32_t start_byte = ts_node_start_byte(match.captures[i].node);
                    const uint32_t end_byte = ts_node_end_byte(match.captures[i].node);
                    if (start_byte > end_byte || end_byte > source_bytes ||
                        (start_byte & 1u) || (end_byte & 1u))
                        return TshPredicateResult::ERROR;
                    const UChar* begin = start_byte == end_byte ? nullptr :
                        reinterpret_cast<const UChar*>(contiguous_source_data + start_byte);
                    const UChar* end = start_byte == end_byte ? nullptr :
                        reinterpret_cast<const UChar*>(contiguous_source_data + end_byte);
                    const int matched = tshPredicateRegexMatchesContiguous(
                        compiled->regex, begin, end);
                    if (matched < 0) return TshPredicateResult::ERROR;
                    all_match = all_match && (matched == 1);
                    all_not_match = all_not_match && (matched == 0);
                }
            } else if (text_source &&
                compiled->execution_capability == RegexExecutionCapability::BOUNDED_WINDOW &&
                compiled->maximum_match_bytes > 0) {
                for (uint32_t i = 0; i < match.capture_count; ++i) {
                    if (match.captures[i].index != predicate[1].value_id) continue;
                    has_capture = true;
                    const uint32_t start_byte = ts_node_start_byte(match.captures[i].node);
                    const uint32_t end_byte = ts_node_end_byte(match.captures[i].node);
                    if (start_byte > end_byte || end_byte > source_bytes ||
                        (start_byte & 1u) || (end_byte & 1u))
                        return TshPredicateResult::ERROR;
                    const int matched = tshPredicateRegexMatchesTextSourceBounded(
                        *text_source, start_byte, end_byte, compiled->regex,
                        compiled->maximum_match_bytes);
                    if (matched < 0) return TshPredicateResult::ERROR;
                    all_match = all_match && (matched == 1);
                    all_not_match = all_not_match && (matched == 0);
                }
            } else if (utf16_source) {
                for (uint32_t i = 0; i < match.capture_count; ++i) {
                    if (match.captures[i].index != predicate[1].value_id) continue;
                    has_capture = true;
                    const uint32_t start_byte = ts_node_start_byte(match.captures[i].node);
                    const uint32_t end_byte = ts_node_end_byte(match.captures[i].node);
                    if (start_byte > end_byte || end_byte > source_bytes ||
                        (start_byte & 1u) || (end_byte & 1u))
                        return TshPredicateResult::ERROR;
                    const int matched = tshPredicateRegexMatchesContiguous(
                        compiled->regex, utf16_source + start_byte,
                        utf16_source + end_byte);
                    if (matched < 0) return TshPredicateResult::ERROR;
                    all_match = all_match && (matched == 1);
                    all_not_match = all_not_match && (matched == 0);
                }
            } else {
                if (!tshPredicateCaptureTexts(match, predicate[1].value_id,
                        utf16_source, text_source, source_bytes, actual))
                    return TshPredicateResult::ERROR;
                has_capture = !actual.empty();
                for (const auto& value : actual) {
                    const UChar* begin = value.empty() ? nullptr : value.data();
                    const UChar* end = value.empty() ? nullptr : value.data() + value.size();
                    const int matched = tshPredicateRegexMatchesContiguous(
                        compiled->regex, begin, end);
                    if (matched < 0) return TshPredicateResult::ERROR;
                    all_match = all_match && (matched == 1);
                    all_not_match = all_not_match && (matched == 0);
                }
            }
            all_match = has_capture && all_match;
            accepted = compiled->negate ? all_not_match : all_match;
        } else return TshPredicateResult::REJECT;

        if (!accepted) return TshPredicateResult::REJECT;
        ++predicate_index;
    }
    return TshPredicateResult::ACCEPT;
}

static void appendRegexCaptureSpans(
    regex_t* regex,
    OnigRegion* region,
    const std::unordered_map<int, uint16_t>& numeric_styles,
    const std::unordered_map<std::string, uint16_t>& named_styles,
    int32_t priority,
    uint32_t declaration_order,
    bool allow_overlap,
    uint32_t output_start_byte,
    uint32_t output_end_byte,
    std::vector<HighlightSpan>& output
) {
    if (!regex || !region) return;
    auto append = [&](int group, uint16_t style_id) {
        if (group < 0 || group >= region->num_regs ||
            region->beg[group] < 0 || region->end[group] < 0) return;
        const uint32_t start = static_cast<uint32_t>(region->beg[group]);
        const uint32_t end = static_cast<uint32_t>(region->end[group]);
        if ((start & 1u) || (end & 1u) || start >= end ||
            end <= output_start_byte || start >= output_end_byte) return;
        HighlightSpan span;
        span.start_byte = std::max(start, output_start_byte);
        span.end_byte = std::min(end, output_end_byte);
        span.style_id = style_id;
        span.flags = 0;
        span.priority = priority;
        span.declaration_order = declaration_order;
        span.allow_overlap = allow_overlap ? 1 : 0;
        output.push_back(span);
    };
    for (const auto& entry : numeric_styles) append(entry.first, entry.second);
    // Capture names are usually short ASCII identifiers. Encode those into a
    // stack buffer instead of allocating a UTF-16 vector for every match.
    std::array<uint16_t, 128> short_name_buffer{};
    std::vector<uint16_t> long_name_buffer;
    for (const auto& entry : named_styles) {
        const size_t name_length = entry.first.size();
        uint16_t* encoded_name = short_name_buffer.data();
        if (name_length <= short_name_buffer.size()) {
            for (size_t i = 0; i < name_length; ++i) {
                encoded_name[i] = static_cast<unsigned char>(entry.first[i]);
            }
        } else {
            long_name_buffer.assign(entry.first.begin(), entry.first.end());
            encoded_name = long_name_buffer.data();
        }
        const UChar* name_begin = reinterpret_cast<const UChar*>(encoded_name);
        const UChar* name_end = name_begin + name_length * sizeof(uint16_t);
        const int group = onig_name_to_backref_number(
            regex, name_begin, name_end, region);
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
    for (auto& state : regex_states) {
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
    if (!grammar) return false;
    static std::atomic<uint64_t> next_revision{1};
    uint64_t revision = next_revision.fetch_add(1, std::memory_order_relaxed);
    if (revision == 0) revision = next_revision.fetch_add(1, std::memory_order_relaxed);
    grammar->revision = revision;
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
    const UChar* search_floor,
    uint32_t output_start_byte,
    uint32_t output_end_byte,
    std::vector<HighlightSpan>& out_spans,
    uint32_t* regex_match_count
) {
    struct RegionOwner {
        OnigRegion* value;
        ~RegionOwner() { if (value) onig_region_free(value, 1); }
    } start_owner{onig_region_new()}, end_owner{onig_region_new()};
    if (!start_owner.value || !end_owner.value) throw std::bad_alloc();
    OnigRegion* start_region = start_owner.value;
    OnigRegion* end_region = end_owner.value;
    // Recover an active block at the viewport boundary with a reverse
    // delimiter search. This avoids scanning the whole document prefix on each
    // render while retaining the original block state.
    const UChar* curr = range_start;
    if (search_floor < range_start) {
        RegionOwner previous_start_owner{onig_region_new()};
        RegionOwner previous_end_owner{onig_region_new()};
        if (!previous_start_owner.value || !previous_end_owner.value) throw std::bad_alloc();
        OnigRegion* previous_start = previous_start_owner.value;
        OnigRegion* previous_end = previous_end_owner.value;
        const int start_hit = onig_search(
            rule.onig_start, text_start, text_end,
            range_start, search_floor, previous_start, ONIG_OPTION_NONE);
        const int end_hit = onig_search(
            rule.onig_end, text_start, text_end,
            range_start, search_floor, previous_end, ONIG_OPTION_NONE);
        if (start_hit < 0 && start_hit != ONIG_MISMATCH) {
            throw std::runtime_error("Oniguruma block start recovery failed with status " +
                std::to_string(start_hit));
        }
        if (end_hit < 0 && end_hit != ONIG_MISMATCH) {
            throw std::runtime_error("Oniguruma block end recovery failed with status " +
                std::to_string(end_hit));
        }
        if (start_hit >= 0 &&
            (previous_start->beg[0] < 0 || previous_start->end[0] < previous_start->beg[0])) {
            throw std::runtime_error("Oniguruma returned an invalid recovered block start");
        }
        if (end_hit >= 0 &&
            (previous_end->beg[0] < 0 || previous_end->end[0] < previous_end->beg[0])) {
            throw std::runtime_error("Oniguruma returned an invalid recovered block end");
        }
        if (start_hit >= 0 &&
            (end_hit == ONIG_MISMATCH ||
             previous_start->beg[0] > previous_end->beg[0])) {
            curr = text_start + previous_start->beg[0];
        }
    }

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
        if (hit_start == ONIG_MISMATCH) break;
        if (hit_start < 0) {
            throw std::runtime_error("Oniguruma block start execution failed with status " +
                std::to_string(hit_start));
        }
        if (start_region->beg[0] < 0 || start_region->end[0] < start_region->beg[0]) {
            throw std::runtime_error("Oniguruma returned an invalid block start range");
        }
        if (regex_match_count) ++(*regex_match_count);

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

        if (hit_end < 0 && hit_end != ONIG_MISMATCH) {
            throw std::runtime_error("Oniguruma block end execution failed with status " +
                std::to_string(hit_end));
        }
        if (hit_end >= 0 &&
            (end_region->beg[0] < 0 || end_region->end[0] < end_region->beg[0])) {
            throw std::runtime_error("Oniguruma returned an invalid block end range");
        }
        uint32_t block_end_byte = (hit_end >= 0) ?
            static_cast<uint32_t>(end_region->end[0]) :
            static_cast<uint32_t>(text_end - text_start);

        if ((block_start_byte & 1u) == 0 && (block_end_byte & 1u) == 0) {
            if (rule.has_style && block_start_byte < block_end_byte) {
                HighlightSpan block_span;
                if (block_end_byte > output_start_byte &&
                    block_start_byte < output_end_byte) {
                    block_span.start_byte = std::max(block_start_byte, output_start_byte);
                    block_span.end_byte = std::min(block_end_byte, output_end_byte);
                    block_span.style_id = rule.style_id;
                    block_span.flags = 0;
                    block_span.priority = rule.priority;
                    block_span.declaration_order = rule.declaration_order;
                    block_span.allow_overlap = rule.allow_overlap ? 1 : 0;
                    out_spans.push_back(block_span);
                }
            }
            if (rule.has_content_style) {
                const uint32_t content_start = static_cast<uint32_t>(start_region->end[0]);
                const uint32_t content_end = hit_end >= 0 ?
                    static_cast<uint32_t>(end_region->beg[0]) :
                    static_cast<uint32_t>(text_end - text_start);
                if (content_start < content_end &&
                    (content_start & 1u) == 0 && (content_end & 1u) == 0 &&
                    content_end > output_start_byte && content_start < output_end_byte) {
                    HighlightSpan content_span;
                    content_span.start_byte = std::max(content_start, output_start_byte);
                    content_span.end_byte = std::min(content_end, output_end_byte);
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
                rule.allow_overlap, output_start_byte, output_end_byte, out_spans
            );
            if (hit_end >= 0) {
                appendRegexCaptureSpans(
                    rule.onig_end, end_region, rule.end_group_style_map,
                    rule.end_named_group_style_map, rule.priority, rule.declaration_order,
                    rule.allow_overlap, output_start_byte, output_end_byte, out_spans
                );
            }

            if (!rule.nested_rules.empty()) {
                const UChar* inner_start = content_start;
                const UChar* inner_end = hit_end >= 0 ? text_start + end_region->beg[0] : text_end;

                for (const auto& nested : rule.nested_rules) {
                    if (!nested.enabled) continue;
                    if (nested.type == RegexRuleType::BLOCK && nested.onig_start && nested.onig_end) {
                        const UChar* nested_range_start = std::max(
                            inner_start, text_start + output_start_byte);
                        const UChar* nested_range_end = std::min(
                            inner_end, text_start + output_end_byte);
                        matchBlockRecursive(
                            nested, text_start, text_end,
                            nested_range_start, nested_range_end, inner_start,
                            output_start_byte, output_end_byte, out_spans, regex_match_count);
                        continue;
                    }
                    if (nested.type == RegexRuleType::MATCH && nested.onig_start) {
                        RegionOwner inner_region_owner{onig_region_new()};
                        if (!inner_region_owner.value) throw std::bad_alloc();
                        OnigRegion* inner_region = inner_region_owner.value;
                        const UChar* nested_scan_start = inner_start;
                        const UChar* nested_scan_end = inner_end;
                        if (nested.execution_capability == RegexExecutionCapability::BOUNDED_WINDOW &&
                            nested.maximum_match_bytes > 0) {
                            const UChar* viewport_start = text_start + output_start_byte;
                            const UChar* viewport_end = text_start + output_end_byte;
                            const uint64_t overlap = nested.maximum_match_bytes >= 2u
                                ? nested.maximum_match_bytes - 2u : 0u;
                            if (viewport_start > inner_start) {
                                const uint64_t available = static_cast<uint64_t>(
                                    viewport_start - inner_start);
                                nested_scan_start = viewport_start -
                                    static_cast<ptrdiff_t>(std::min<uint64_t>(overlap, available));
                            }
                            // Oniguruma's forward-search range is also passed to
                            // match_at() as its upper match bound. Keep bounded matches
                            // that begin before the viewport but finish just after its
                            // right edge by extending the search range with the same
                            // proven finite-width context. Captures are clipped to the
                            // viewport in append_nested_capture() below.
                            if (viewport_end < inner_end) {
                                const uint64_t available_after_viewport =
                                    static_cast<uint64_t>(inner_end - viewport_end);
                                const uint64_t right_context =
                                    std::min<uint64_t>(overlap, available_after_viewport);
                                nested_scan_end = viewport_end +
                                    static_cast<ptrdiff_t>(right_context);
                            } else {
                                nested_scan_end = inner_end;
                            }
                        }
                        const UChar* inner_curr = nested_scan_start;
                        while (inner_curr < nested_scan_end) {
                            int inner_hit = onig_search(
                                nested.onig_start,
                                text_start,
                                text_end,
                                inner_curr,
                                nested_scan_end,
                                inner_region,
                                ONIG_OPTION_NONE
                            );
                            if (inner_hit == ONIG_MISMATCH) break;
                            if (inner_hit < 0) {
                                throw std::runtime_error(
                                    "Oniguruma nested block Regex execution failed with status " +
                                    std::to_string(inner_hit));
                            }
                            if (inner_region->beg[0] < 0 ||
                                inner_region->end[0] < inner_region->beg[0]) {
                                throw std::runtime_error(
                                    "Oniguruma returned an invalid nested Regex match range");
                            }
                            if (regex_match_count) ++(*regex_match_count);

                            auto append_nested_capture = [&](int group, uint16_t style_id) {
                                if (group < 0 || group >= inner_region->num_regs ||
                                    inner_region->beg[group] < 0 || inner_region->end[group] < 0) return;
                                const uint32_t in_s = static_cast<uint32_t>(inner_region->beg[group]);
                                const uint32_t in_e = static_cast<uint32_t>(inner_region->end[group]);
                                if ((in_s & 1u) != 0 || (in_e & 1u) != 0 ||
                                    in_e <= output_start_byte || in_s >= output_end_byte) return;
                                HighlightSpan in_span;
                                in_span.start_byte = std::max(in_s, output_start_byte);
                                in_span.end_byte = std::min(in_e, output_end_byte);
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
                                advanceOneUtf16CodePoint(text_start + inner_region->beg[0], nested_scan_end) :
                                text_start + inner_region->end[0];
                        }

                    }
                }
            }
        }

        if (hit_end >= 0 && end_region->end[0] > start_region->beg[0]) {
            const UChar* next = text_start + end_region->end[0];
            if (next <= curr) next = advanceOneUtf16CodePoint(curr, range_end);
            curr = next;
        } else {
            // An unterminated block extends to EOF; no later top-level block can
            // start after it, so stop after emitting the visible intersection.
            break;
        }
    }

}

static bool collectQueryRootRanges(
    TSTree* tree,
    const std::unordered_set<TSSymbol>& root_symbols,
    uint32_t range_start,
    uint32_t range_end,
    uint32_t total_bytes,
    std::vector<std::pair<uint32_t, uint32_t>>* ranges,
    std::unordered_set<TSSymbol>* found_symbols
) {
    if (!tree || root_symbols.empty() || !ranges || !found_symbols ||
        range_start >= range_end) return false;

    TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));
    bool done = false;
    try {
        while (!done) {
            const TSNode node = ts_tree_cursor_current_node(&cursor);
            const uint32_t start = ts_node_start_byte(node);
            const uint32_t end = ts_node_end_byte(node);
            const bool zero_width = start == end;

            // Nodes are visited in source order. Once a non-empty node starts
            // at/after the viewport end, no later sibling can contribute.
            if (!zero_width && start >= range_end) break;

            const bool intersects = zero_width
                ? (start >= range_start && start <= range_end)
                : (start < range_end && end > range_start);
            if (intersects) {
                const TSSymbol symbol = ts_node_symbol(node);
                if (root_symbols.find(symbol) != root_symbols.end() &&
                    start <= end && end <= total_bytes &&
                    (start & 1u) == 0 && (end & 1u) == 0) {
                    uint32_t dependency_start = start;
                    uint32_t dependency_end = end;
                    // Missing/zero-width nodes still depend on their insertion
                    // boundary. Expand by one UTF-16 code unit on each side.
                    if (dependency_start == dependency_end) {
                        dependency_start = dependency_start >= 2u
                            ? dependency_start - 2u : 0u;
                        dependency_end = static_cast<uint32_t>(std::min<uint64_t>(
                            total_bytes, static_cast<uint64_t>(dependency_end) + 2u));
                    }
                    ranges->emplace_back(dependency_start, dependency_end);
                    found_symbols->insert(symbol);
                }

                // Jump directly to the first child that contains or follows
                // range_start. The previous implementation descended from the
                // first child of every intersecting ancestor, then scanned all
                // preceding siblings. On a file-root node this could walk most
                // of a huge AST for every small viewport request.
                if (ts_tree_cursor_goto_first_child_for_byte(&cursor, range_start) >= 0)
                    continue;
            }

            while (!ts_tree_cursor_goto_next_sibling(&cursor)) {
                if (!ts_tree_cursor_goto_parent(&cursor)) {
                    done = true;
                    break;
                }
            }
        }
    } catch (...) {
        ts_tree_cursor_delete(&cursor);
        throw;
    }
    ts_tree_cursor_delete(&cursor);
    return found_symbols->size() == root_symbols.size();
}

static void appendQueryPatternDependencies(
    const TshGrammar& grammar,
    const TSLanguage* language,
    TSTree* tree,
    uint32_t range_start,
    uint32_t range_end,
    uint32_t total_bytes,
    std::vector<HighlightDependencyRange>* output
) {
    if (!output) return;

    std::unordered_set<TSSymbol> root_symbols;
    bool has_active_pattern = false;
    bool requires_document_scope = false;
    for (const auto& query : grammar.native_queries) {
        if (!query.enabled || !query.query) continue;
        for (const auto& item : query.pattern_metadata) {
            const auto& metadata = item.second;
            if (!metadata.enabled) continue;
            has_active_pattern = true;
            if (metadata.dependency_scope !=
                    NativeQueryProgram::QueryDependencyScope::ROOTED_LOCAL ||
                metadata.root_node_symbol == 0 || language == nullptr) {
                requires_document_scope = true;
                break;
            }
            root_symbols.insert(metadata.root_node_symbol);
        }
        if (requires_document_scope) break;
    }
    if (!has_active_pattern) return;
    if (requires_document_scope || root_symbols.empty()) {
        output->push_back({
            0u, total_bytes, UINT32_MAX,
            static_cast<uint8_t>(HighlightSource::NATIVE_QUERY), 0xFFu,
            HIGHLIGHT_DEPENDENCY_DOCUMENT_WIDE
        });
        return;
    }

    // All fixed-root patterns share one source-ordered AST traversal. The
    // union of root intervals is sufficient for cache invalidation and avoids
    // repeating the same viewport walk once per distinct node symbol.
    std::vector<std::pair<uint32_t, uint32_t>> root_ranges;
    std::unordered_set<TSSymbol> found_symbols;
    found_symbols.reserve(root_symbols.size());
    if (!collectQueryRootRanges(
            tree, root_symbols, range_start, range_end, total_bytes,
            &root_ranges, &found_symbols)) {
        // A missing candidate root is not proof that an edit elsewhere
        // cannot create a root covering this viewport.
        output->push_back({
            0u, total_bytes, UINT32_MAX,
            static_cast<uint8_t>(HighlightSource::NATIVE_QUERY), 0xFFu,
            HIGHLIGHT_DEPENDENCY_DOCUMENT_WIDE
        });
        return;
    }

    std::sort(root_ranges.begin(), root_ranges.end());
    size_t merged_count = 0;
    for (const auto& range : root_ranges) {
        if (merged_count != 0 && range.first <= root_ranges[merged_count - 1u].second) {
            root_ranges[merged_count - 1u].second =
                std::max(root_ranges[merged_count - 1u].second, range.second);
        } else {
            root_ranges[merged_count++] = range;
        }
    }
    root_ranges.resize(merged_count);
    const uint8_t viewport_flags = static_cast<uint8_t>(
        range_start == 0u && range_end == total_bytes
            ? HIGHLIGHT_DEPENDENCY_DOCUMENT_WIDE : 0u);
    output->push_back({
        range_start, range_end, UINT32_MAX,
        static_cast<uint8_t>(HighlightSource::NATIVE_QUERY),
        static_cast<uint8_t>(NativeQueryProgram::QueryDependencyScope::ROOTED_LOCAL),
        viewport_flags
    });
    for (const auto& range : root_ranges) {
        uint8_t flags = HIGHLIGHT_DEPENDENCY_BOUNDARY_SENSITIVE;
        if (range.first == 0u && range.second == total_bytes) {
            flags = static_cast<uint8_t>(flags | HIGHLIGHT_DEPENDENCY_DOCUMENT_WIDE);
        }
        output->push_back({
            range.first, range.second, UINT32_MAX,
            static_cast<uint8_t>(HighlightSource::NATIVE_QUERY),
            static_cast<uint8_t>(NativeQueryProgram::QueryDependencyScope::ROOTED_LOCAL),
            flags
        });
    }
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
    uint32_t range_end_byte,
    const windbell::largefile::TextSource* text_source,
    std::vector<RegexStateCheckpoint>* regex_state_checkpoints,
    uint64_t source_version,
    uint64_t partition_version,
    std::vector<HighlightDependencyRange>* dependency_ranges
) {
    std::vector<HighlightSpan> final_spans;
    if (dependency_ranges) dependency_ranges->clear();
    if (diagnostic_match_counts) std::fill(diagnostic_match_counts, diagnostic_match_counts + 4, 0u);
    if (length_chars != 0 && input_chars == nullptr && !text_source) return final_spans;
    if (length_chars > (std::numeric_limits<uint32_t>::max() / sizeof(uint16_t))) return final_spans;
    auto grammar = getGrammarByExtension(extension);
    if (!grammar) return final_spans;

    const uint64_t source_bytes = text_source ? text_source->sizeBytes() :
        static_cast<uint64_t>(length_chars) * sizeof(uint16_t);
    if (source_bytes > std::numeric_limits<uint32_t>::max() ||
        (source_bytes & 1u) != 0 ||
        source_bytes != static_cast<uint64_t>(length_chars) * sizeof(uint16_t)) return final_spans;
    const uint32_t total_bytes = static_cast<uint32_t>(source_bytes);
    if (range_end_byte == UINT32_MAX) range_end_byte = total_bytes;
    if ((range_start_byte & 1u) != 0 || (range_end_byte & 1u) != 0 ||
        range_start_byte > range_end_byte || range_end_byte > total_bytes) {
        return final_spans;
    }
    if (range_start_byte == range_end_byte) return final_spans;
    // Avoid pointer arithmetic on a null input pointer for an empty document.
    // Empty buffers are valid inputs (for example, immediately after clearing an editor).
    static const UChar kEmptyInput = 0;
    const char *contiguous_source_data = nullptr;
    uint64_t contiguous_source_size = 0;
    const bool source_is_contiguous = text_source &&
        text_source->contiguousData(&contiguous_source_data, &contiguous_source_size) &&
        contiguous_source_size == total_bytes &&
        (total_bytes == 0 || contiguous_source_data != nullptr);
    const bool has_contiguous_text = input_chars != nullptr || source_is_contiguous;
    const UChar* text_start = input_chars
        ? reinterpret_cast<const UChar*>(input_chars)
        : (source_is_contiguous && contiguous_source_data
            ? reinterpret_cast<const UChar*>(contiguous_source_data)
            : &kEmptyInput);
    const UChar* text_end = has_contiguous_text ? text_start + total_bytes : text_start;

    // Keep intermediate capture memory proportional to the requested viewport.
    // Regex rules scan from document start to preserve anchors and state transitions,
    // but captures outside this byte interval are discarded
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
        (input_chars != nullptr || text_source != nullptr || total_bytes == 0);

    std::vector<HighlightSpan> micro_spans;
    // Size scratch capacity from the requested viewport, not the full document.
    // A tiny viewport in a huge file should not reserve thousands of spans.
    const size_t viewport_chars =
        static_cast<size_t>((range_end_byte - range_start_byte) / sizeof(uint16_t));
    micro_spans.reserve(std::min<size_t>(viewport_chars / 4, 4096));

    // Large Piece Trees do not expose a document-sized contiguous pointer.
    // Execute the compiler-proven finite-width MATCH subset directly against
    // bounded TextSource windows. This restores ordinary ATS/Regex captures
    // for large files without rebuilding a 32 MiB+ compatibility buffer.
    // Anchors, block rules, and stateful tokenizers remain on their dedicated
    // context-aware paths; they are never silently reinterpreted as window-local.
    if (text_source) {
        // Finite-width regexes run only over the requested viewport plus their
        // proven maximum-match overlap. mmap contiguity must not trigger a full
        // document scan for every editor draw.
        constexpr uint64_t kRegexCoreWindowBytes = 256u * 1024u;
        // Reuse one bounded PieceTree/file window across all partitions and
        // Regex rules. Its capacity is released with this highlight call.
        std::vector<UChar> bounded_regex_window;
        std::vector<UChar> encoded_group_name;
        for (const auto& rule : grammar->regex_rules) {
            if (!rule.enabled || rule.type != RegexRuleType::MATCH ||
                !rule.onig_start ||
                rule.execution_capability != RegexExecutionCapability::BOUNDED_WINDOW ||
                rule.maximum_match_bytes == 0) continue;
            const uint64_t overlap = rule.maximum_match_bytes >= 2u
                ? rule.maximum_match_bytes - 2u : 0u;
            uint64_t core_start = range_start_byte > overlap
                ? range_start_byte - overlap : 0u;
            core_start &= ~uint64_t{1};
            if (core_start != 0) {
                bool splits_surrogate_pair = false;
                if (!windbell::largefile::checkUtf16SurrogateBoundary(
                        *text_source, core_start, &splits_surrogate_pair)) {
                    throw std::runtime_error(
                        "TSH could not validate the bounded Regex start boundary");
                }
                if (splits_surrogate_pair) core_start -= 2u;
            }
            // Reuse one Oniguruma region across all core windows for this rule.
            // Region allocation is not proportional to the number of 256 KiB
            // partitions and remains exception-safe for the whole rule execution.
            OnigRegion* region = onig_region_new();
            if (!region) throw std::bad_alloc();
            struct WindowRegionGuard {
                OnigRegion* value;
                ~WindowRegionGuard() { if (value) onig_region_free(value, 1); }
            } region_guard{region};
            while (core_start < range_end_byte) {
                const uint64_t core_end = std::min<uint64_t>(
                    range_end_byte, core_start + kRegexCoreWindowBytes);
                const uint64_t remaining_after_core = total_bytes - core_end;
                const uint64_t window_end = overlap >= remaining_after_core
                    ? total_bytes : core_end + overlap;
                const uint64_t window_bytes = window_end - core_start;
                if (window_bytes == 0 || (window_bytes & 1u) != 0 ||
                    window_bytes > static_cast<uint64_t>(std::numeric_limits<size_t>::max()) /
                        sizeof(UChar)) {
                    throw std::runtime_error("TSH bounded Regex window has an invalid byte range");
                }
                uint64_t window_base_byte = core_start;
                const UChar* window_start = nullptr;
                const UChar* window_end_ptr = nullptr;
                const UChar* current = nullptr;
                const UChar* search_range_end = nullptr;
                if (has_contiguous_text) {
                    // Both legacy JNI input_chars and TextSource-backed contiguous
                    // storage are already UTF-16LE buffers. Use the original bytes
                    // directly; do not copy a 256 KiB scratch window per rule or
                    // viewport partition. Keep full-document bounds for anchors
                    // and lookbehind while restricting match starts to this core.
                    window_base_byte = 0;
                    window_start = text_start;
                    window_end_ptr = text_end;
                    current = text_start + core_start;
                    search_range_end = text_start + core_end;
                } else {
                    // PieceTree/file-window sources expose borrowed chunks, so
                    // Oniguruma still needs a bounded contiguous materialization.
                    // Let bad_alloc reach the JNI boundary; silently breaking here
                    // would return a partial highlight set as if it were complete.
                    bounded_regex_window.resize(
                        static_cast<size_t>(window_bytes / sizeof(UChar)));
                    if (!windbell::largefile::copyTextRange(
                            *text_source, core_start, window_bytes, bounded_regex_window.data())) {
                        throw std::runtime_error("TSH could not read a bounded Regex window");
                    }
                    window_start = bounded_regex_window.data();
                    window_end_ptr = window_start + bounded_regex_window.size();
                    current = window_start;
                    search_range_end = window_end_ptr;
                }
                while (current < search_range_end) {
                    const int hit = onig_search(
                        rule.onig_start, window_start, window_end_ptr,
                        current, search_range_end, region, ONIG_OPTION_NONE);
                    if (hit == ONIG_MISMATCH) break;
                    if (hit < 0) {
                        throw std::runtime_error(
                            "Oniguruma bounded Regex execution failed with status " +
                            std::to_string(hit));
                    }
                    const int64_t local_start = region->beg[0];
                    const int64_t local_end = region->end[0];
                    if (local_start < 0 || local_end < local_start ||
                        local_end > static_cast<int64_t>(source_is_contiguous ? total_bytes : window_bytes) ||
                        (local_start & 1LL) != 0 || (local_end & 1LL) != 0) {
                        throw std::runtime_error(
                            "Oniguruma returned an invalid bounded UTF-16 match range");
                    }
                    const uint64_t absolute_start = window_base_byte +
                        static_cast<uint64_t>(local_start);
                    const uint64_t absolute_end = window_base_byte +
                        static_cast<uint64_t>(local_end);
                    if (absolute_start >= core_start && absolute_start < core_end &&
                        absolute_end <= (source_is_contiguous ? total_bytes : window_end) &&
                        (absolute_start & 1u) == 0 &&
                        (absolute_end & 1u) == 0) {
                        if (diagnostic_match_counts) tshIncrementDiagnostic(&diagnostic_match_counts[0]);
                        auto append_window_capture = [&](int group, uint16_t style_id) {
                            if (group < 0 || group >= region->num_regs ||
                                region->beg[group] < 0 || region->end[group] < 0) return;
                            const uint64_t capture_start = window_base_byte +
                                static_cast<uint64_t>(region->beg[group]);
                            const uint64_t capture_end = window_base_byte +
                                static_cast<uint64_t>(region->end[group]);
                            if (capture_start >= capture_end ||
                                capture_end <= range_start_byte ||
                                capture_start >= range_end_byte ||
                                capture_end > total_bytes ||
                                (capture_start & 1u) || (capture_end & 1u)) return;
                            HighlightSpan span;
                            span.start_byte = static_cast<uint32_t>(
                                std::max<uint64_t>(capture_start, range_start_byte));
                            span.end_byte = static_cast<uint32_t>(
                                std::min<uint64_t>(capture_end, range_end_byte));
                            span.style_id = style_id;
                            span.flags = 0;
                            span.priority = rule.priority;
                            span.declaration_order = rule.declaration_order;
                            span.allow_overlap = rule.allow_overlap ? 1 : 0;
                            span.source = static_cast<uint8_t>(HighlightSource::ONIG_REGEX);
                            micro_spans.push_back(span);
                        };
                        if (rule.group_style_map.empty() &&
                            rule.named_group_style_map.empty()) {
                            if (rule.has_style) append_window_capture(0, rule.style_id);
                        } else {
                            auto whole = rule.group_style_map.find(0);
                            if (whole != rule.group_style_map.end())
                                append_window_capture(0, whole->second);
                            for (int group = 1; group < region->num_regs; ++group) {
                                auto style = rule.group_style_map.find(group);
                                if (style != rule.group_style_map.end())
                                    append_window_capture(group, style->second);
                            }
                            for (const auto& named : rule.named_group_style_map) {
                                encoded_group_name.assign(
                                    named.first.begin(), named.first.end());
                                const int group = onig_name_to_backref_number(
                                    rule.onig_start, encoded_group_name.data(),
                                    encoded_group_name.data() + encoded_group_name.size(), region);
                                append_window_capture(group, named.second);
                            }
                        }
                    }
                    // Oniguruma offsets are UTF-16LE byte offsets. A zero-width
                    // match must advance by one complete Unicode code point, not
                    // merely one UTF-16 code unit (which can split a surrogate pair).
                    const UChar* next = local_end > local_start
                        ? window_start + local_end
                        : advanceOneUtf16CodePoint(
                            window_start + local_start, window_end_ptr);
                    if (next <= current || next >= search_range_end) break;
                    current = next;
                }
                core_start = core_end;
            }
        }
    }

    if (has_contiguous_text) for (const auto& rule : grammar->regex_rules) {
        if (!rule.enabled) continue;
        // Compiler-proven finite-width MATCH rules have already run against
        // bounded TextSource windows above. Do not rescan the entire mapped
        // document for every viewport request merely because a contiguous
        // mmap view is available for context-dependent rules.
        if (text_source && rule.type == RegexRuleType::MATCH &&
            rule.execution_capability == RegexExecutionCapability::BOUNDED_WINDOW) {
            continue;
        }
        if (rule.type == RegexRuleType::BLOCK && rule.onig_start && rule.onig_end) {
            matchBlockRecursive(
                rule, text_start, text_end,
                text_start + range_start_byte, text_start + range_end_byte, text_start,
                range_start_byte, range_end_byte, micro_spans,
                diagnostic_match_counts ? &diagnostic_match_counts[0] : nullptr);
            retain_requested_range(micro_spans);
            continue;
        }

        if (!rule.onig_start) continue;

        OnigRegion* region = onig_region_new();
        if (!region) throw std::bad_alloc();
        struct ContiguousRegionGuard {
            OnigRegion* value;
            ~ContiguousRegionGuard() { if (value) onig_region_free(value, 1); }
        } region_guard{region};
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
                        const uint32_t capture_start = static_cast<uint32_t>(region->beg[group]);
                        const uint32_t capture_end = static_cast<uint32_t>(region->end[group]);
                        if (capture_end <= range_start_byte || capture_start >= range_end_byte ||
                            (capture_start & 1u) || (capture_end & 1u)) return;
                        HighlightSpan capture;
                        capture.start_byte = std::max(capture_start, range_start_byte);
                        capture.end_byte = std::min(capture_end, range_end_byte);
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
                if (hit_pos != ONIG_MISMATCH) {
                    throw std::runtime_error(
                        "Oniguruma contiguous Regex execution failed with status " +
                        std::to_string(hit_pos));
                }
                break;
            }
        }
        retain_requested_range(micro_spans);
    }
    retain_requested_range(micro_spans);

    // Tag regex spans before the stateful tokenizer appends its own results.
    for (auto& span : micro_spans) {
        span.source = static_cast<uint8_t>(HighlightSource::ONIG_REGEX);
    }
    const size_t regex_state_span_begin = micro_spans.size();

    // Stateful Regex rules run inside the same Regex pipeline and emit the same
    // capture ABI as ordinary Regex rules; there is no separate token model.
    constexpr uint64_t kMaxRegexStateContextFallbackBytes = 50ull * 1024ull * 1024ull;
    if (!grammar->regex_states.empty()) {
        // Keep checkpoint density proportional to document size. 64 KiB is the
        // minimum stride for ordinary files; larger documents spread the same
        // target checkpoint count across the full source instead of discarding
        // the oldest checkpoints after an arbitrary fixed prefix.
        constexpr uint64_t kMinimumRegexStateCheckpointStrideBytes = 64ull * 1024ull;
        constexpr uint64_t kTargetRegexStateCheckpointCount = 8192ull;
        const uint64_t scaled_stride =
            static_cast<uint64_t>(total_bytes) / kTargetRegexStateCheckpointCount +
            (static_cast<uint64_t>(total_bytes) % kTargetRegexStateCheckpointCount != 0 ? 1ull : 0ull);
        const uint64_t regex_state_checkpoint_stride =
            std::max(kMinimumRegexStateCheckpointStrideBytes, scaled_stride);
        std::vector<std::string> state_stack{grammar->regex_start_state};
        uint32_t position = 0;
        uint64_t next_checkpoint = regex_state_checkpoint_stride;
        // Checkpoints are reusable only for the exact immutable source and
        // partition snapshots from which their tokenizer state was produced.
        if (regex_state_checkpoints) {
            // A checkpoint is valid only for the exact immutable source and
            // partition snapshots that produced it. Byte offsets alone cannot
            // establish that an edited suffix is unchanged, so invalidate the
            // complete checkpoint set whenever either version changes.
            // Checkpoints are inserted in byte-offset order and the complete
            // vector is invalidated whenever the source/partition generation
            // changes. The first/last entries therefore prove the generation
            // for the entire retained set without scanning up to 8192 records.
            if (!regex_state_checkpoints->empty()) {
                const auto& first = regex_state_checkpoints->front();
                const auto& last = regex_state_checkpoints->back();
                const bool stale =
                    first.source_version != source_version ||
                    first.partition_version != partition_version ||
                    last.source_version != source_version ||
                    last.partition_version != partition_version;
                if (stale) regex_state_checkpoints->clear();
            }

            const RegexStateCheckpoint* nearest = nullptr;
            const auto after_viewport = std::upper_bound(
                regex_state_checkpoints->begin(), regex_state_checkpoints->end(),
                static_cast<uint64_t>(range_start_byte),
                [](uint64_t offset, const RegexStateCheckpoint& checkpoint) {
                    return offset < checkpoint.byte_offset;
                });
            if (after_viewport != regex_state_checkpoints->begin()) {
                const auto candidate = std::prev(after_viewport);
                if (!candidate->state_stack.empty()) nearest = &*candidate;
            }
            if (nearest) {
                position = static_cast<uint32_t>(nearest->byte_offset);
                state_stack = nearest->state_stack;
                next_checkpoint =
                    ((static_cast<uint64_t>(position) / regex_state_checkpoint_stride) + 1u) *
                    regex_state_checkpoint_stride;
            }
        }
        // Dependencies begin at the nearest reusable state checkpoint; the
        // cached state is valid only from that byte offset through this scan.
        uint32_t state_dependency_start = position;
        auto save_regex_state_checkpoint = [&]() {
            if (!regex_state_checkpoints || position < next_checkpoint) return;

            auto insertion = std::lower_bound(
                regex_state_checkpoints->begin(), regex_state_checkpoints->end(),
                position,
                [](const RegexStateCheckpoint& candidate, uint64_t offset) {
                    return candidate.byte_offset < offset;
                });
            const bool already_current = insertion != regex_state_checkpoints->end() &&
                insertion->byte_offset == position &&
                insertion->source_version == source_version &&
                insertion->partition_version == partition_version;
            if (!already_current) {
                RegexStateCheckpoint checkpoint;
                checkpoint.byte_offset = position;
                checkpoint.source_version = source_version;
                checkpoint.partition_version = partition_version;
                checkpoint.state_stack = state_stack;
                regex_state_checkpoints->insert(insertion, std::move(checkpoint));
            }

            next_checkpoint =
                ((static_cast<uint64_t>(position) / regex_state_checkpoint_stride) + 1u) *
                regex_state_checkpoint_stride;
        };
        OnigRegion* state_region = onig_region_new();
        OnigRegion* state_case_region = onig_region_new();
        if (!state_region || !state_case_region) {
            if (state_region) onig_region_free(state_region, 1);
            if (state_case_region) onig_region_free(state_case_region, 1);
            // Never turn an allocation failure into a successful but incomplete
            // highlight result. JNI maps this to OutOfMemoryError.
            throw std::bad_alloc();
        }
        uint32_t zero_width_steps = 0;
        std::vector<UChar> state_window;
        uint32_t state_window_start = 0;
        uint32_t state_window_end = total_bytes;
        const uint64_t state_window_match_width = grammar->regex_state_graph_maximum_match_bytes;
        bool state_full_source_fallback = false;
        // Context-sensitive patterns retain whole-document Oniguruma semantics
        // when the source fits the explicit 50 MiB fallback budget. The buffer
        // is sized to the actual document (not preallocated to the budget), so
        // smaller documents consume only their own source size. This is a hard
        // ceiling, not a claim that arbitrary context-sensitive patterns can
        // be evaluated correctly in independent windows.
        if (!has_contiguous_text && text_source && !grammar->regex_state_graph_bounded &&
            static_cast<uint64_t>(total_bytes) <= kMaxRegexStateContextFallbackBytes) {
            const uint64_t required_units = static_cast<uint64_t>(total_bytes) / sizeof(UChar);
            if (required_units <= static_cast<uint64_t>(state_window.max_size())) {
                try { state_window.resize(static_cast<size_t>(required_units)); }
                catch (...) { state_window.clear(); }
            }
            if (state_window.size() == total_bytes / sizeof(UChar) &&
                windbell::largefile::copyTextRange(*text_source, 0, total_bytes, state_window.data())) {
                state_full_source_fallback = true;
            } else {
                state_window.clear();
            }
        }
        auto ensure_state_window = [&]() -> bool {
            if (has_contiguous_text || state_full_source_fallback) {
                state_window_start = 0;
                state_window_end = total_bytes;
                return true;
            }
            if (!text_source || !grammar->regex_state_graph_bounded ||
                state_window_match_width == 0) return false;
            const uint64_t remaining = static_cast<uint64_t>(total_bytes) - position;
            if (!state_window.empty() && position >= state_window_start &&
                position < state_window_end &&
                static_cast<uint64_t>(state_window_end - position) >= state_window_match_width) {
                return true;
            }
            constexpr uint64_t kStateCoreWindowBytes = 256u * 1024u;
            const uint64_t requested = state_window_match_width > std::numeric_limits<uint64_t>::max() - kStateCoreWindowBytes
                ? UINT64_MAX : kStateCoreWindowBytes + state_window_match_width;
            const uint64_t bytes = std::min<uint64_t>(remaining, requested);
            if (bytes == 0 || (bytes & 1u) != 0 ||
                bytes > static_cast<uint64_t>(std::numeric_limits<size_t>::max()) / sizeof(UChar)) return false;
            try { state_window.resize(static_cast<size_t>(bytes / sizeof(UChar))); }
            catch (...) { return false; }
            if (!windbell::largefile::copyTextRange(*text_source, position, bytes, state_window.data())) return false;
            state_window_start = position;
            state_window_end = static_cast<uint32_t>(static_cast<uint64_t>(position) + bytes);
            return true;
        };
        // Tokenize only through the requested viewport. The final match may
        // extend past range_end_byte (needed for correct token boundaries and
        // state transitions), but the next iteration must not scan the tail of
        // a multi-hundred-megabyte document merely to answer one viewport.
        while (position < total_bytes && position < range_end_byte) {
            if (!ensure_state_window()) {
                // Never return a seemingly successful but incomplete highlight
                // result when a context-sensitive state graph cannot be backed
                // by a complete source buffer. Bounded graphs use source windows;
                // unbounded graphs retain whole-document Oniguruma semantics.
                // The caller must see an explicit failure rather than silently
                // losing every stateful capture after an arbitrary size limit.
                throw std::runtime_error(
                    "TSH Regex State execution requires whole-document context, "
                    "but a complete compatible input buffer could not be provided");
            }
            const UChar* state_text_start = has_contiguous_text ? text_start : state_window.data();
            const UChar* state_text_end = has_contiguous_text ? text_end : state_window.data() + state_window.size();
            const uint32_t local_position = position - state_window_start;
            const UChar* state_position_ptr = state_text_start + local_position;
            const RegexStateRule* matched_rule = nullptr;
            // Include cycles and missing states are rejected during Grammar
            // compilation. Use a self-recursive generic lambda so the hot
            // per-code-point tokenizer does not construct std::function or an
            // unordered_set<string> for every token probe.
            const auto find_rule = [&](auto&& self,
                                       const std::string& state_name) -> const RegexStateRule* {
                const auto state = grammar->regex_states.find(state_name);
                if (state == grammar->regex_states.end()) return nullptr;
                for (const auto& candidate : state->second) {
                    if (!candidate.enabled) continue;
                    if (!candidate.include_state.empty()) {
                        if (const RegexStateRule* included =
                                self(self, candidate.include_state)) {
                            return included;
                        }
                    }
                    if (!candidate.pattern) continue;
                    const int length = onig_match(
                        candidate.pattern,
                        state_text_start,
                        state_text_end,
                        state_position_ptr,
                        state_region,
                        ONIG_OPTION_NONE
                    );
                    if (length >= 0) return &candidate;
                    if (length != ONIG_MISMATCH) {
                        throw std::runtime_error(
                            "Oniguruma Regex State match failed with status " +
                            std::to_string(length));
                    }
                }
                return nullptr;
            };

            const std::string& active_state = state_stack.empty() ?
                grammar->regex_start_state : state_stack.back();
            matched_rule = find_rule(find_rule, active_state);
            if (!matched_rule) {
                const UChar* next = advanceOneUtf16CodePoint(state_position_ptr, state_text_end);
                const uint32_t end = state_window_start + static_cast<uint32_t>(next - state_text_start);
                if (grammar->regex_has_default_style && end > position &&
                    end > range_start_byte && position < range_end_byte) {
                    HighlightSpan span;
                    span.start_byte = std::max(position, range_start_byte);
                    span.end_byte = std::min(end, range_end_byte);
                    span.style_id = grammar->regex_default_style_id;
                    span.priority = 0;
                    span.declaration_order = 0;
                    micro_spans.push_back(span);
                }
                position = end;
                zero_width_steps = 0;
                save_regex_state_checkpoint();
                continue;
            }

            if (diagnostic_match_counts) tshIncrementDiagnostic(&diagnostic_match_counts[1]);
            const uint32_t match_start = state_region->beg[0] >= 0 ?
                state_window_start + static_cast<uint32_t>(state_region->beg[0]) : position;
            const uint32_t match_end = state_region->end[0] >= 0 ?
                state_window_start + static_cast<uint32_t>(state_region->end[0]) : position;
            uint16_t resolved_style_id = matched_rule->style_id;
            bool has_resolved_style = matched_rule->has_style;
            if (!matched_rule->cases.empty()) {
                const RegexStateCase* default_case = nullptr;
                bool case_matched = false;
                for (const auto& case_entry : matched_rule->cases) {
                    if (case_entry.is_default) {
                        default_case = &case_entry;
                        continue;
                    }
                    if (!case_entry.matcher) continue;
                    const int case_length = onig_match(
                        case_entry.matcher,
                        state_text_start,
                        state_text_end,
                        state_text_start + (match_start - state_window_start),
                        state_case_region,
                        ONIG_OPTION_NONE
                    );
                    if (case_length >= 0 &&
                        match_start + static_cast<uint32_t>(case_length) == match_end) {
                        resolved_style_id = case_entry.style_id;
                        has_resolved_style = true;
                        case_matched = true;
                        break;
                    }
                    if (case_length != ONIG_MISMATCH) {
                        throw std::runtime_error(
                            "Oniguruma Regex State case match failed with status " +
                            std::to_string(case_length));
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

            // Track transition changes directly instead of copying the complete
            // state stack for every token. Most tokenizer matches do not change
            // state, so this avoids repeated vector/string allocations on the hot path.
            bool state_changed = false;
            if (matched_rule->pop || matched_rule->next_state == "@pop") {
                if (state_stack.size() > 1) {
                    state_stack.pop_back();
                    state_changed = true;
                }
            } else if (matched_rule->next_state == "@popall") {
                state_changed = state_stack.size() != 1 ||
                    state_stack.front() != grammar->regex_start_state;
                if (state_changed) state_stack.assign(1, grammar->regex_start_state);
            } else if (matched_rule->next_state == "@push") {
                state_stack.push_back(state_stack.back());
                state_changed = true;
            } else if (!matched_rule->next_state.empty()) {
                std::string_view target(matched_rule->next_state);
                if (!target.empty() && target.front() == '@') target.remove_prefix(1);
                if (state_stack.back() != target) {
                    state_stack.back().assign(target.data(), target.size());
                    state_changed = true;
                }
            } else if (!matched_rule->push_state.empty()) {
                std::string_view target(matched_rule->push_state);
                if (!target.empty() && target.front() == '@') target.remove_prefix(1);
                state_stack.emplace_back(target);
                state_changed = true;
            } else if (!matched_rule->switch_state.empty()) {
                std::string_view target(matched_rule->switch_state);
                if (!target.empty() && target.front() == '@') target.remove_prefix(1);
                if (state_stack.back() != target) {
                    state_stack.back().assign(target.data(), target.size());
                    state_changed = true;
                }
            }

            if (matched_rule->rematch && state_changed) {
                if (++zero_width_steps > 64) {
                    const UChar* next = advanceOneUtf16CodePoint(
                        state_text_start + (position - state_window_start), state_text_end);
                    position = state_window_start + static_cast<uint32_t>(next - state_text_start);
                    zero_width_steps = 0;
                }
            } else if (match_end > position) {
                position = match_end;
                zero_width_steps = 0;
                save_regex_state_checkpoint();
            } else {
                const UChar* next = advanceOneUtf16CodePoint(
                    state_text_start + (position - state_window_start), state_text_end);
                position = state_window_start + static_cast<uint32_t>(next - state_text_start);
                if (++zero_width_steps > 64) zero_width_steps = 0;
                save_regex_state_checkpoint();
            }
        }
        onig_region_free(state_case_region, 1);
        onig_region_free(state_region, 1);
        if (dependency_ranges) {
            if (!grammar->regex_state_graph_bounded || state_full_source_fallback) {
                dependency_ranges->push_back({
                    0u, total_bytes, UINT32_MAX,
                    static_cast<uint8_t>(HighlightSource::REGEX_STATE), 0xFFu,
                    HIGHLIGHT_DEPENDENCY_DOCUMENT_WIDE
                });
            } else {
                const uint64_t range_lookahead =
                    state_window_match_width >
                        std::numeric_limits<uint64_t>::max() - range_end_byte
                    ? UINT64_MAX
                    : static_cast<uint64_t>(range_end_byte) + state_window_match_width;
                const uint64_t lookahead_end = std::max<uint64_t>(
                    position, range_lookahead);
                const uint32_t dependency_end = static_cast<uint32_t>(
                    std::min<uint64_t>(total_bytes, lookahead_end));
                uint8_t flags = static_cast<uint8_t>(
                    HIGHLIGHT_DEPENDENCY_CHECKPOINT_BASED |
                    HIGHLIGHT_DEPENDENCY_BOUNDARY_SENSITIVE);
                if (state_dependency_start == 0u && dependency_end == total_bytes) {
                    flags = static_cast<uint8_t>(
                        flags | HIGHLIGHT_DEPENDENCY_DOCUMENT_WIDE);
                }
                dependency_ranges->push_back({
                    state_dependency_start, dependency_end, UINT32_MAX,
                    static_cast<uint8_t>(HighlightSource::REGEX_STATE), 0xFFu, flags
                });
            }
        }
    }
    for (size_t i = regex_state_span_begin; i < micro_spans.size(); ++i) {
        micro_spans[i].source = static_cast<uint8_t>(HighlightSource::REGEX_STATE);
    }
    retain_requested_range(micro_spans);

    std::vector<HighlightSpan> macro_spans;
    if (tree && tree_source_valid && !grammar->ast_selector_rules.empty()) {
        // AST/query capture density is grammar-dependent. Start with a small
        // bounded capacity and grow only when real captures are produced.
        macro_spans.reserve(std::min<size_t>(viewport_chars / 6, 4096));
        TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));
        std::vector<const AstSelectorRule*> candidate_rules;
        candidate_rules.reserve(grammar->ast_wildcard_rules.size() + 16);
        // Candidate rules are already indexed by node/owner symbol. Deduplicate
        // those small index lists with an epoch table instead of a linear
        // std::find for every candidate on every visited syntax node.
        std::vector<uint32_t> candidate_seen(grammar->ast_selector_rules.size(), 0u);
        uint32_t candidate_epoch = 0u;

        while (true) {
            if (++candidate_epoch == 0u) {
                std::fill(candidate_seen.begin(), candidate_seen.end(), 0u);
                candidate_epoch = 1u;
            }
            TSNode node = ts_tree_cursor_current_node(&cursor);
            const uint32_t current_start_byte = ts_node_start_byte(node);
            const uint32_t current_end_byte = ts_node_end_byte(node);
            // Tree-sitter nodes are ordered by source position. Stop once the
            // cursor reaches the first node after this result partition.
            if (current_start_byte >= range_end_byte) break;
            TSSymbol sym = ts_node_symbol(node);
            TSFieldId fid = ts_tree_cursor_current_field_id(&cursor);

            TSNode parent = ts_node_parent(node);
            TSSymbol parent_sym = ts_node_is_null(parent) ? 0 : ts_node_symbol(parent);
            const TSSymbol parent_grammar_sym = ts_node_is_null(parent) ? 0 :
                ts_node_grammar_symbol(parent);

            candidate_rules.clear();
            auto append_indexed_rules = [&](const std::vector<uint32_t>& indices) {
                for (uint32_t index : indices) {
                    if (index >= grammar->ast_selector_rules.size() ||
                        candidate_seen[index] == candidate_epoch) continue;
                    candidate_seen[index] = candidate_epoch;
                    candidate_rules.push_back(&grammar->ast_selector_rules[index]);
                }
            };
            auto append_indexed_type_rules = [&](const auto& index, const char* type) {
                if (!type) return;
                // Both type indexes use transparent std::less<> lookup. Avoid
                // allocating a temporary std::string for every visited node.
                const auto found = index.find(std::string_view(type));
                if (found != index.end()) append_indexed_rules(found->second);
            };
            append_indexed_rules(grammar->ast_wildcard_rules);
            const char* node_type = ts_node_type(node);
            const char* node_grammar_type = ts_node_grammar_type(node);
            append_indexed_type_rules(grammar->ast_rules_by_node_type, node_type);
            append_indexed_type_rules(grammar->ast_supertype_rules_by_node_type, node_type);
            if (!node_grammar_type || !node_type || std::strcmp(node_grammar_type, node_type) != 0) {
                append_indexed_type_rules(grammar->ast_rules_by_node_type, node_grammar_type);
                append_indexed_type_rules(grammar->ast_supertype_rules_by_node_type, node_grammar_type);
            }
            auto node_rules = grammar->ast_rules_by_node_symbol.find(sym);
            if (node_rules != grammar->ast_rules_by_node_symbol.end()) {
                append_indexed_rules(node_rules->second);
            }
            const TSSymbol node_grammar_sym = ts_node_grammar_symbol(node);
            if (node_grammar_sym != sym) {
                auto grammar_node_rules =
                    grammar->ast_rules_by_node_symbol.find(node_grammar_sym);
                if (grammar_node_rules != grammar->ast_rules_by_node_symbol.end()) {
                    append_indexed_rules(grammar_node_rules->second);
                }
            }
            if (!ts_node_is_null(parent)) {
                auto field_rules = grammar->ast_field_rules_by_parent_symbol.find(parent_sym);
                if (field_rules != grammar->ast_field_rules_by_parent_symbol.end()) {
                    append_indexed_rules(field_rules->second);
                }
                const char* parent_type = ts_node_type(parent);
                const char* parent_grammar_type = ts_node_grammar_type(parent);
                append_indexed_type_rules(grammar->ast_field_rules_by_parent_type, parent_type);
                append_indexed_type_rules(grammar->ast_supertype_field_rules_by_parent_type, parent_type);
                if (!parent_grammar_type || !parent_type || std::strcmp(parent_grammar_type, parent_type) != 0) {
                    append_indexed_type_rules(grammar->ast_field_rules_by_parent_type, parent_grammar_type);
                    append_indexed_type_rules(grammar->ast_supertype_field_rules_by_parent_type, parent_grammar_type);
                }
                // Field selectors are indexed by the compiled owner symbol.
                // Also dispatch through the original grammar identity when an
                // aliased parent exposes a different public symbol.
                if (parent_grammar_sym != parent_sym) {
                    auto grammar_field_rules =
                        grammar->ast_field_rules_by_parent_symbol.find(parent_grammar_sym);
                    if (grammar_field_rules != grammar->ast_field_rules_by_parent_symbol.end()) {
                        append_indexed_rules(grammar_field_rules->second);
                    }
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
                    const char* parent_type = ts_node_is_null(parent) ? nullptr : ts_node_type(parent);
                    const char* parent_grammar_type = ts_node_is_null(parent) ? nullptr : ts_node_grammar_type(parent);
                    // The compile-time dispatch index already expanded the
                    // target supertype's transitive subtype closure. Re-checking
                    // it recursively for every viewport node only repeats that work.
                    const bool owner_symbol_matches = ast_rule.target_symbol != 0 &&
                        (ast_rule.target_symbol == parent_sym ||
                         ast_rule.target_symbol == parent_grammar_sym);
                    const bool owner_type_matches = !ast_rule.target_type.empty() &&
                        ((parent_type && ast_rule.target_type == parent_type) ||
                         (parent_grammar_type && ast_rule.target_type == parent_grammar_type));
                    if (ts_node_is_null(parent) ||
                        (!owner_symbol_matches && !owner_type_matches) ||
                        ast_rule.field_id != fid) {
                        continue;
                    }
                } else {
                    // Candidate dispatch is keyed by both visible and grammar
                    // symbols and includes all compiled supertype descendants.
                    // Exact identity here is sufficient and avoids a recursive
                    // language-subtype walk for every candidate node.
                    const bool node_symbol_matches = ast_rule.target_symbol != 0 &&
                        (ast_rule.target_symbol == sym ||
                         ast_rule.target_symbol == node_grammar_sym);
                    const bool node_type_matches = !ast_rule.target_type.empty() &&
                        ((node_type && ast_rule.target_type == node_type) ||
                         (node_grammar_type && ast_rule.target_type == node_grammar_type));
                    if (!node_symbol_matches && !node_type_matches) continue;
                }
                if (ast_rule.parent_symbol != 0 || !ast_rule.parent_type.empty()) {
                    const char* parent_type = ts_node_is_null(parent) ? nullptr : ts_node_type(parent);
                    const char* parent_grammar_type = ts_node_is_null(parent) ? nullptr : ts_node_grammar_type(parent);
                    const bool parent_symbol_matches = ast_rule.parent_symbol != 0 &&
                        tshAstNodeMatchesSelector(*grammar, parent,
                            ast_rule.parent_symbol, ast_rule.parent_type);
                    const bool parent_type_matches = !ast_rule.parent_type.empty() &&
                        ((parent_type && ast_rule.parent_type == parent_type) ||
                         (parent_grammar_type && ast_rule.parent_type == parent_grammar_type));
                    if (!parent_symbol_matches && !parent_type_matches) continue;
                }
                if (ast_rule.ancestor_symbol != 0) {
                    bool ancestor_found = false;
                    TSNode ancestor = parent;
                    while (!ts_node_is_null(ancestor)) {
                        // A node can expose an alias as its public symbol/type while
                        // retaining a different grammar symbol/type. ATS names are
                        // resolved against the grammar, so match both identities.
                        const char* visible_type = ts_node_type(ancestor);
                        const char* grammar_type = ts_node_grammar_type(ancestor);
                        const bool symbol_matches =
                            tshAstNodeMatchesSelector(*grammar, ancestor,
                                ast_rule.ancestor_symbol, ast_rule.ancestor_type);
                        const bool type_matches = !ast_rule.ancestor_type.empty() &&
                            ((visible_type && ast_rule.ancestor_type == visible_type) ||
                             (grammar_type && ast_rule.ancestor_type == grammar_type));
                        if (symbol_matches || type_matches) {
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
                        TSNode child = ts_node_child(node, child_index);
                        const char* child_visible_type = ts_node_type(child);
                        const char* child_grammar_type = ts_node_grammar_type(child);
                        if (tshAstNodeMatchesSelector(*grammar, child,
                                ast_rule.child_symbol, ast_rule.child_type) ||
                            (!ast_rule.child_type.empty() &&
                             ((child_visible_type && ast_rule.child_type == child_visible_type) ||
                              (child_grammar_type && ast_rule.child_type == child_grammar_type)))) {
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

                    // Literal-only selectors and compiler-proven bounded text
                    // regexes are evaluated directly against TextSource windows.
                    // Context-dependent regexes use the guarded contiguous adapter
                    // below and are never reinterpreted as window-local patterns.
                    if (text_source && !ast_rule.text_regex) {
                        if (ast_rule.has_text_equals &&
                            !tshSourceMatchesUtf8Literal(*text_source, text_start, text_end,
                                ast_rule.text_equals, 0)) continue;
                        if (ast_rule.has_text_prefix &&
                            !tshSourceMatchesUtf8Literal(*text_source, text_start, text_end,
                                ast_rule.text_prefix, 1)) continue;
                        if (ast_rule.has_text_suffix &&
                            !tshSourceMatchesUtf8Literal(*text_source, text_start, text_end,
                                ast_rule.text_suffix, 2)) continue;
                    } else {
                    // A bounded AST text-regex is searched directly against the
                    // TextSource in fixed windows. Never materialize a potentially
                    // enormous syntax node just to evaluate a selector predicate.
                    if (text_source && ast_rule.text_regex &&
                        ast_rule.text_regex_bounded) {
                        if (ast_rule.has_text_equals &&
                            !tshSourceMatchesUtf8Literal(*text_source, text_start, text_end,
                                ast_rule.text_equals, 0)) continue;
                        if (ast_rule.has_text_prefix &&
                            !tshSourceMatchesUtf8Literal(*text_source, text_start, text_end,
                                ast_rule.text_prefix, 1)) continue;
                        if (ast_rule.has_text_suffix &&
                            !tshSourceMatchesUtf8Literal(*text_source, text_start, text_end,
                                ast_rule.text_suffix, 2)) continue;
                        if (!tshSourceRegexMatchesBounded(
                                *text_source, text_start, text_end,
                                ast_rule.text_regex,
                                ast_rule.text_regex_maximum_bytes)) continue;
                    } else {
                    // Context-dependent AST regexes retain the contiguous adapter,
                    // but cap the temporary node to prevent a single giant string or
                    // comment node from allocating a document-sized second copy.
                    constexpr uint64_t kMaxContiguousAstRegexBytes = 4ULL * 1024ULL * 1024ULL;
                    if (text_source && ast_rule.text_regex &&
                        text_end - text_start > kMaxContiguousAstRegexBytes) {
                        throw std::runtime_error(
                            "TSH context-dependent AST Regex exceeds the 4 MiB contiguous-node limit; "
                            "refusing to publish an incomplete highlight result");
                    }
                    std::string node_text_storage;
                    std::vector<UChar> node_text_window((text_end - text_start) / sizeof(UChar));
                    const UChar* node_source = nullptr;
                    if (text_end > text_start) {
                        if (text_source) {
                            if (!windbell::largefile::copyTextRange(
                                    *text_source, text_start, text_end - text_start,
                                    node_text_window.data())) {
                                throw std::runtime_error(
                                    "TSH could not read the AST text predicate's TextSource range");
                            }
                            node_source = node_text_window.data();
                        } else if (has_contiguous_text) {
                            const UChar *document_source = input_chars
                                ? reinterpret_cast<const UChar*>(input_chars)
                                : reinterpret_cast<const UChar*>(contiguous_source_data);
                            node_source = document_source + text_start;
                        } else {
                            throw std::runtime_error(
                                "TSH AST text predicate has no readable source buffer");
                        }
                    } else node_source = &kEmptyInput;
                    if (!tshUtf16SliceToUtf8(node_source, text_end - text_start, node_text_storage)) {
                        throw std::runtime_error(
                            "TSH AST text predicate encountered malformed UTF-16 input");
                    }
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
                        // node_source already addresses this exact node slice,
                        // whether it came from a small contiguous buffer or a bounded
                        // TextSource copy. Never fall back to document-global offsets
                        // when the large-file path has no contiguous input pointer.
                        const UChar* regex_begin = node_source;
                        const UChar* regex_end = node_source +
                            (text_end - text_start) / sizeof(UChar);
                        OnigRegion* regex_region = onig_region_new();
                        if (!regex_region) throw std::bad_alloc();
                        const int regex_result = onig_search(
                            ast_rule.text_regex, regex_begin, regex_end,
                            regex_begin, regex_end, regex_region, ONIG_OPTION_NONE);
                        onig_region_free(regex_region, 1);
                        if (regex_result == ONIG_MISMATCH) continue;
                        if (regex_result < 0) {
                            throw std::runtime_error(
                                "Oniguruma AST text Regex execution failed with status " +
                                std::to_string(regex_result));
                        }
                    }
                    }
                    }
                }

                // Only positional child-index selectors need a parent-wide
                // sibling scan. First/last and adjacent-sibling selectors use
                // Tree-sitter's direct sibling links and avoid O(child_count)
                // work for every candidate in large declarations/argument lists.
                if (ast_rule.child_index != UINT32_MAX ||
                    ast_rule.named_child_index != UINT32_MAX) {
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
                }
                if (ast_rule.first_child >= 0) {
                    const bool is_first = ts_node_is_null(ts_node_prev_sibling(node));
                    if (is_first != (ast_rule.first_child != 0)) continue;
                }
                if (ast_rule.last_child >= 0) {
                    const bool is_last = ts_node_is_null(ts_node_next_sibling(node));
                    if (is_last != (ast_rule.last_child != 0)) continue;
                }
                if (ast_rule.previous_sibling_symbol != 0 ||
                    !ast_rule.previous_sibling_type.empty()) {
                    TSNode previous = ts_node_prev_sibling(node);
                    if (ts_node_is_null(previous)) continue;
                    const char* previous_type = ts_node_type(previous);
                    const char* previous_grammar_type = ts_node_grammar_type(previous);
                    const bool previous_symbol_matches =
                        tshAstNodeMatchesSelector(*grammar, previous,
                            ast_rule.previous_sibling_symbol, ast_rule.previous_sibling_type);
                    const bool previous_type_matches =
                        (!ast_rule.previous_sibling_type.empty() && previous_type &&
                            ast_rule.previous_sibling_type == previous_type) ||
                        (!ast_rule.previous_sibling_type.empty() && previous_grammar_type &&
                            ast_rule.previous_sibling_type == previous_grammar_type);
                    if (!previous_symbol_matches && !previous_type_matches) continue;
                }
                if (ast_rule.next_sibling_symbol != 0 ||
                    !ast_rule.next_sibling_type.empty()) {
                    TSNode next = ts_node_next_sibling(node);
                    if (ts_node_is_null(next)) continue;
                    const char* next_type = ts_node_type(next);
                    const char* next_grammar_type = ts_node_grammar_type(next);
                    const bool next_symbol_matches =
                        tshAstNodeMatchesSelector(*grammar, next,
                            ast_rule.next_sibling_symbol, ast_rule.next_sibling_type);
                    const bool next_type_matches =
                        (!ast_rule.next_sibling_type.empty() && next_type &&
                            ast_rule.next_sibling_type == next_type) ||
                        (!ast_rule.next_sibling_type.empty() && next_grammar_type &&
                            ast_rule.next_sibling_type == next_grammar_type);
                    if (!next_symbol_matches && !next_type_matches) continue;
                }

                if (diagnostic_match_counts) tshIncrementDiagnostic(&diagnostic_match_counts[2]);
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
                    span.priority = ast_rule.priority > 0 ? ast_rule.priority : 100;
                    span.declaration_order = ast_rule.declaration_order;
                    span.allow_overlap = ast_rule.allow_overlap ? 1 : 0;
                    if (span.end_byte > range_start_byte && span.start_byte < range_end_byte) {
                        span.start_byte = std::max(span.start_byte, range_start_byte);
                        span.end_byte = std::min(span.end_byte, range_end_byte);
                        macro_spans.push_back(span);
                    }
                }
            }

            // If this node contains the requested start offset, jump
            // directly to the first intersecting child. This avoids walking
            // every preceding sibling subtree for each partition request while
            // still evaluating the ancestor nodes needed by parent/ancestor
            // selectors. Once inside the partition, ordinary DFS visits all
            // overlapping descendants and following siblings only.
            // A subtree ending at/before the viewport start cannot contribute
            // any capture to this render. Skip it as a whole: descending into
            // its children would still make a late-file viewport O(all preceding
            // AST nodes), despite the containing-node jump below.
            const bool subtree_before_viewport =
                current_end_byte <= range_start_byte;
            if (!subtree_before_viewport &&
                current_start_byte < range_start_byte &&
                current_end_byte > range_start_byte &&
                ts_tree_cursor_goto_first_child_for_byte(&cursor, range_start_byte) >= 0) {
                continue;
            }
            if (!subtree_before_viewport && ts_tree_cursor_goto_first_child(&cursor)) continue;
            if (ts_tree_cursor_goto_next_sibling(&cursor)) continue;

            // Advance to the next sibling of the nearest ancestor. This is
            // shared by normal DFS completion and whole-subtree viewport skips.
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
        appendQueryPatternDependencies(
            *grammar, language, tree, range_start_byte, range_end_byte,
            total_bytes, dependency_ranges);
        // Each .tsh query block has its own native TSQuery and capture namespace.
        // This preserves per-block priority/order and avoids concatenating query
        // sources into a synthetic aggregate program.
        for (const auto& native_query : grammar->native_queries) {
            if (!native_query.enabled || !native_query.query) continue;
#if defined(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
            std::unique_ptr<TSQueryCursor, decltype(&ts_query_cursor_delete)> q_cursor(
                ts_query_cursor_new(), &ts_query_cursor_delete);
            if (!q_cursor) throw std::bad_alloc();
            TSQueryCursor* cursor = q_cursor.get();

            // Restrict query traversal to the requested range while retaining the
            // complete document AST and absolute node coordinates. A fixed match
            // limit or artificial progress timeout would remove valid captures
            // from dense files, so use the maximum representable pending capacity.
            if (!ts_query_cursor_set_byte_range(cursor, range_start_byte, range_end_byte)) {
                throw std::runtime_error(
                    "TSH could not apply the requested byte range to a Tree-sitter query cursor");
            }
            ts_query_cursor_set_match_limit(cursor, UINT32_MAX);
            ts_query_cursor_exec(cursor, native_query.query, ts_tree_root_node(tree));

            // Keep each query block transactional without staging a second
            // full-sized capture vector. Record a checkpoint in the shared macro
            // output, append captures directly, and roll back this block if the
            // cursor reports an incomplete traversal. This halves the transient
            // storage attributable to the per-block capture staging vector while
            // preserving all-or-nothing publication semantics.
            const size_t query_span_checkpoint = macro_spans.size();
            TSQueryMatch match;
            uint64_t match_order = 0;
            uint64_t accepted_match_count = 0;
            while (ts_query_cursor_next_match(cursor, &match)) {
                const uint64_t current_order = match_order++;
                auto pattern_metadata = native_query.pattern_metadata.find(match.pattern_index);
                if (pattern_metadata != native_query.pattern_metadata.end() &&
                    !pattern_metadata->second.enabled) {
                    continue;
                }
                // text_start is a sentinel pointer when a PieceTree/TextSource
                // is non-contiguous. Never pass that sentinel as readable UTF-16
                // memory to predicate helpers; let them use the TextSource window API.
                const TshPredicateResult predicate_result = tshEvaluateNativePredicates(
                    native_query, match,
                    has_contiguous_text ? text_start : nullptr,
                    has_contiguous_text ? nullptr : text_source, total_bytes);
                if (predicate_result == TshPredicateResult::ERROR) {
                    throw std::runtime_error(
                        "TSH native query predicate evaluation failed while reading capture text");
                }
                if (predicate_result == TshPredicateResult::REJECT) continue;
                ++accepted_match_count;
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
                    span.allow_overlap = pattern_metadata != native_query.pattern_metadata.end() &&
                        pattern_metadata->second.allow_overlap ? 1u : 0u;
                    const uint64_t order = static_cast<uint64_t>(native_query.declaration_order) +
                        pattern_order + current_order + capture_index;
                    span.declaration_order = static_cast<uint32_t>(
                        std::min<uint64_t>(order, UINT32_MAX));
                    macro_spans.push_back(span);
                }
            }
            const bool query_incomplete = ts_query_cursor_did_exceed_match_limit(cursor);
            if (!query_incomplete) {
                if (diagnostic_match_counts) {
                    tshIncrementDiagnostic(&diagnostic_match_counts[3], accepted_match_count);
                }
            } else {
                // A Tree-sitter cursor drops the earliest in-progress match when
                // its capture-list pool is exhausted. Returning the remaining
                // matches as an ordinary highlight result would therefore make
                // the output look complete while silently omitting valid
                // captures. Roll back this block and fail the whole highlight
                // operation so JNI/Kotlin can report the incomplete execution.
                macro_spans.resize(query_span_checkpoint);
                throw std::runtime_error(
                    "TSH native query '" + native_query.id +
                    "' exceeded Tree-sitter's pending capture-list capacity; "
                    "the query result is incomplete");
            }
            q_cursor.reset();
        }
    }
#endif // TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS
    for (size_t i = native_query_span_begin; i < macro_spans.size(); ++i) {
        macro_spans[i].source = static_cast<uint8_t>(HighlightSource::NATIVE_QUERY);
    }
    retain_requested_range(macro_spans);

    // Keep the public range API's output bounded to the requested document
    // window. Ordinary bounded Regex, bounded Regex State graphs, and native
    // Tree-sitter queries can operate on TextSource windows/ranges. Context-
    // dependent Regex State graphs retain whole-source semantics only through
    // the explicitly capped contiguous fallback above; larger sources require
    // a future context-aware streaming strategy and are not reinterpreted.
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
    std::sort(final_spans.begin(), final_spans.end(),
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

    // Resolve only strict interval crossings. The previous implementation
    // compared every candidate against every accepted span (O(n^2)); dense AST
    // queries with hundreds of thousands of captures became unusable. This
    // augmented interval treap indexes accepted non-overlap spans and prunes
    // nested intervals, which are deliberately preserved as layered captures.
    std::vector<HighlightSpan> resolved_spans;
    resolved_spans.reserve(final_spans.size());
    HighlightSpanConflictIndex conflict_index;
    std::vector<HighlightSpan> fragments;
    std::vector<HighlightSpan> remaining;
    std::vector<size_t> conflicts;
    std::vector<std::pair<uint32_t, uint32_t>> cuts;
    fragments.reserve(4);
    remaining.reserve(4);
    conflicts.reserve(8);
    cuts.reserve(8);

    for (const auto& candidate : final_spans) {
        fragments.clear();
        fragments.push_back(candidate);
        bool removedAny = true;
        while (removedAny && !fragments.empty()) {
            removedAny = false;
            remaining.clear();
            for (const auto& fragment : fragments) {
                if (fragment.allow_overlap) {
                    remaining.push_back(fragment);
                    continue;
                }
                conflict_index.findOverlaps(
                    fragment.start_byte, fragment.end_byte, resolved_spans, conflicts);
                if (conflicts.empty()) {
                    remaining.push_back(fragment);
                    continue;
                }

                cuts.clear();
                for (size_t acceptedIndex : conflicts) {
                    if (acceptedIndex >= resolved_spans.size()) continue;
                    const auto& accepted = resolved_spans[acceptedIndex];
                    const bool sameRange = fragment.start_byte == accepted.start_byte &&
                        fragment.end_byte == accepted.end_byte;
                    const bool fragmentContains =
                        fragment.start_byte <= accepted.start_byte &&
                        fragment.end_byte >= accepted.end_byte;
                    const bool acceptedContains =
                        accepted.start_byte <= fragment.start_byte &&
                        accepted.end_byte >= fragment.end_byte;
                    const bool nested = !sameRange &&
                        (fragmentContains || acceptedContains);
                    // Exact-range captures are independent semantic layers, not
                    // partial interval conflicts. Keep them all so the downstream
                    // style resolver can apply rule priority without erasing
                    // otherwise valid ATS, Query, or Regex candidates.
                    if (sameRange || nested ||
                        fragment.allow_overlap || accepted.allow_overlap) continue;
                    const uint32_t cutStart = std::max(
                        fragment.start_byte, accepted.start_byte);
                    const uint32_t cutEnd = std::min(
                        fragment.end_byte, accepted.end_byte);
                    if (cutStart < cutEnd) cuts.emplace_back(cutStart, cutEnd);
                }
                if (cuts.empty()) {
                    remaining.push_back(fragment);
                    continue;
                }
                std::sort(cuts.begin(), cuts.end());
                uint32_t cursor = fragment.start_byte;
                for (const auto& cut : cuts) {
                    if (cut.second <= cursor) continue;
                    if (cut.first > cursor) {
                        HighlightSpan left = fragment;
                        left.start_byte = cursor;
                        left.end_byte = cut.first;
                        remaining.push_back(left);
                    }
                    cursor = std::max(cursor, cut.second);
                    if (cursor >= fragment.end_byte) break;
                }
                if (cursor < fragment.end_byte) {
                    HighlightSpan right = fragment;
                    right.start_byte = cursor;
                    remaining.push_back(right);
                }
                removedAny = true;
            }
            fragments.swap(remaining);
        }

        for (const auto& fragment : fragments) {
            const size_t acceptedIndex = resolved_spans.size();
            resolved_spans.push_back(fragment);
            conflict_index.add(fragment, acceptedIndex, resolved_spans);
        }
    }

    // Rejoin adjacent fragments when every semantic attribute is identical.
    std::sort(resolved_spans.begin(), resolved_spans.end(),
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
    // Merge in place after sorting. A second full-sized vector is unnecessary:
    // write_index never advances beyond read_index, so unread entries stay intact.
    size_t write_index = 0;
    for (size_t read_index = 0; read_index < resolved_spans.size(); ++read_index) {
        const HighlightSpan span = resolved_spans[read_index];
        if (write_index != 0) {
            auto& previous = resolved_spans[write_index - 1];
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
        if (write_index != read_index) resolved_spans[write_index] = span;
        ++write_index;
    }
    resolved_spans.resize(write_index);
    final_spans = std::move(resolved_spans);

    std::sort(final_spans.begin(), final_spans.end(), [](const HighlightSpan& a, const HighlightSpan& b) {
        if (a.start_byte != b.start_byte) return a.start_byte < b.start_byte;
        // Apply outer captures first, then inner/high-priority captures.
        if (a.end_byte != b.end_byte) return a.end_byte > b.end_byte;
        if (a.priority != b.priority) return a.priority > b.priority;
        if (a.declaration_order != b.declaration_order)
            return a.declaration_order < b.declaration_order;
        if (a.source != b.source) return a.source < b.source;
        if (a.style_id != b.style_id) return a.style_id < b.style_id;
        if (a.allow_overlap != b.allow_overlap) return a.allow_overlap < b.allow_overlap;
        return a.flags < b.flags;
    });

    return final_spans;
}

} // namespace windbell::tsh