#include "NativeHighlightCache.h"
#include "QueryDependencyAnalyzer.h"

#include <cassert>
#include <cstdint>
#include <memory>
#include <utility>
#include <vector>

using windbell::tsh::HighlightSource;
using windbell::tsh::HighlightSpan;
using windbell::tsh::NativeHighlightCache;
using windbell::tsh::SharedNativeMemoryBudget;
using windbell::tsh::analyzeSimpleQueryRoot;
using windbell::tsh::queryPredicateRequiresAncestorContext;
using windbell::tsh::HIGHLIGHT_DEPENDENCY_DOCUMENT_WIDE;
using windbell::tsh::HIGHLIGHT_DEPENDENCY_BOUNDARY_SENSITIVE;
using windbell::tsh::HIGHLIGHT_DEPENDENCY_CHECKPOINT_BASED;

namespace {
NativeHighlightCache::Key key(uint64_t version, uint32_t start, uint32_t end) {
    NativeHighlightCache::Key result;
    result.version = version;
    result.source_version = version;
    result.partition_version = version;
    result.grammar_revision = 7;
    result.start_byte = start;
    result.end_byte = end;
    return result;
}

NativeHighlightCache::Value value(uint32_t span_start, uint32_t span_end) {
    NativeHighlightCache::Value result;
    HighlightSpan span{};
    span.start_byte = span_start;
    span.end_byte = span_end;
    span.style_id = 3;
    span.source = static_cast<uint8_t>(HighlightSource::NATIVE_QUERY);
    result.spans.push_back(span);
    return result;
}

std::vector<NativeHighlightCache::DependencyRange> dependencies(
    uint32_t start, uint32_t end,
    uint8_t source = static_cast<uint8_t>(HighlightSource::NATIVE_QUERY),
    uint8_t flags = 0u) {
    return {{start, end, 2u, source, 0u, flags}};
}

void testSimpleRootAnalyzer() {
    auto root = analyzeSimpleQueryRoot("(identifier) @name");
    assert(root.valid && root.node_type == "identifier" && root.is_named);
    root = analyzeSimpleQueryRoot(
        "(function_definition name: (identifier) @name) @function");
    assert(root.valid && root.node_type == "function_definition");
    root = analyzeSimpleQueryRoot(
        "(identifier) @name (#eq? @name \"main\")");
    assert(root.valid && root.node_type == "identifier");
    root = analyzeSimpleQueryRoot("(\",\") @comma");
    assert(root.valid && root.node_type == "," && !root.is_named);
    assert(!analyzeSimpleQueryRoot("(_) @wildcard").valid);
    assert(!analyzeSimpleQueryRoot("[(identifier) (type_identifier)] @name").valid);
    assert(!analyzeSimpleQueryRoot("((identifier) @name)").valid);
    assert(!analyzeSimpleQueryRoot("(identifier) @name .").valid);
    assert(queryPredicateRequiresAncestorContext("#has-parent?"));
    assert(queryPredicateRequiresAncestorContext("has-ancestor?"));
    assert(queryPredicateRequiresAncestorContext("any-not-has-parent?"));
    assert(!queryPredicateRequiresAncestorContext("#kind-eq?"));
    assert(!queryPredicateRequiresAncestorContext("#match?"));
}

void testUnrelatedEditKeepsAndRebasesEntry() {
    NativeHighlightCache cache;
    cache.put(key(1, 400, 500), value(420, 430), dependencies(410, 450));
    const auto stats = cache.rebaseAfterEdit(2, 2, 2, 100, 110, 4, {{100, 104}});
    assert(stats.payload_before == 1 && stats.payload_after == 1);
    assert(stats.dependencies_before == 1 && stats.dependencies_after == 1);
    assert(stats.viewport_intersects_edit == 0 && stats.dirty_range_invalidated == 0);
    const auto *rebased = cache.find(key(2, 394, 494));
    assert(rebased && rebased->spans[0].start_byte == 414);
    assert(rebased->spans[0].end_byte == 424);
    const auto retained = cache.snapshotRanges(false, 7, 2, 2, 2);
    assert(retained.size() == 1 && retained[0] == std::make_pair(394u, 494u));
}

void testEditInsideDependencyEvictsEntry() {
    NativeHighlightCache cache;
    cache.put(key(1, 100, 200), value(120, 130), dependencies(110, 150));
    cache.rebaseAfterEdit(2, 2, 2, 125, 128, 2, {{125, 127}});
    assert(cache.size() == 0);
}

void testChangedTreeRangeEvictsDependency() {
    NativeHighlightCache cache;
    cache.put(key(1, 100, 200), value(120, 130), dependencies(110, 150));
    const auto stats =
        cache.rebaseAfterEdit(2, 2, 2, 400, 402, 2, {{400, 402}, {135, 145}});
    assert(stats.dirty_range_invalidated == 1);
    assert(stats.payload_before == 1 && stats.payload_after == 0);
    assert(cache.size() == 0);
}

void testMissingDependencyIsNeverReused() {
    NativeHighlightCache cache;
    NativeHighlightCache::Value no_proof;
    HighlightSpan span{};
    span.start_byte = 20;
    span.end_byte = 30;
    no_proof.spans.push_back(span);
    cache.put(key(1, 10, 40), std::move(no_proof), {});
    const auto stats = cache.rebaseAfterEdit(2, 2, 2, 100, 100, 2, {{100, 102}});
    assert(stats.missing_dependency_proof == 1);
    assert(stats.payload_without_proof == 1);
    assert(cache.size() == 0);
}

void testInsertionAtDependencyStartShiftsSnapshot() {
    NativeHighlightCache cache;
    cache.put(key(1, 200, 300), value(220, 230), dependencies(120, 160));
    cache.rebaseAfterEdit(2, 2, 2, 120, 120, 4, {{120, 124}});
    const auto *rebased = cache.find(key(2, 204, 304));
    assert(rebased && rebased->spans[0].start_byte == 224);
    const auto retained = cache.snapshotRanges(false, 7, 2, 2, 2);
    assert(retained.size() == 1 && retained[0] == std::make_pair(204u, 304u));
}

void testInsertionInsideDependencyEvictsSnapshot() {
    NativeHighlightCache cache;
    cache.put(key(1, 100, 200), value(120, 130), dependencies(110, 150));
    cache.rebaseAfterEdit(2, 2, 2, 125, 125, 2, {{125, 127}});
    assert(cache.size() == 0);
}

void testDocumentWideDependencyInvalidatesBoundaryInsertion() {
    NativeHighlightCache cache;
    cache.put(key(1, 10, 40), value(20, 30), dependencies(
        0, 100, static_cast<uint8_t>(HighlightSource::NATIVE_QUERY),
        HIGHLIGHT_DEPENDENCY_DOCUMENT_WIDE));
    const auto stats = cache.rebaseAfterEdit(2, 2, 2, 0, 0, 2, {{0, 2}});
    assert(stats.document_wide_dependency == 1);
    assert(stats.payload_before == 1 && stats.payload_after == 0);
    assert(cache.size() == 0);
}

void testRegexDependencyInvalidatesBoundaryInsertion() {
    NativeHighlightCache cache;
    cache.put(key(1, 210, 250), value(220, 230), dependencies(
        200, 260, static_cast<uint8_t>(HighlightSource::ONIG_REGEX),
        HIGHLIGHT_DEPENDENCY_BOUNDARY_SENSITIVE));
    cache.rebaseAfterEdit(2, 2, 2, 200, 200, 2, {{200, 202}});
    assert(cache.size() == 0);

    NativeHighlightCache adjacent_cache;
    adjacent_cache.put(key(1, 210, 250), value(220, 230), dependencies(
        200, 260, static_cast<uint8_t>(HighlightSource::ONIG_REGEX),
        HIGHLIGHT_DEPENDENCY_BOUNDARY_SENSITIVE));
    // Deleting immediately after the old window changes its right-boundary
    // context even though the half-open byte ranges do not overlap.
    adjacent_cache.rebaseAfterEdit(2, 2, 2, 260, 262, 0, {{260, 260}});
    assert(adjacent_cache.size() == 0);
}

void testCheckpointDependencyInvalidatesEarlierEdits() {
    NativeHighlightCache cache;
    cache.put(key(1, 140, 180), value(150, 160), dependencies(
        100, 200, static_cast<uint8_t>(HighlightSource::REGEX_STATE),
        static_cast<uint8_t>(HIGHLIGHT_DEPENDENCY_CHECKPOINT_BASED |
            HIGHLIGHT_DEPENDENCY_BOUNDARY_SENSITIVE)));

    // The edit is before the recorded scan interval, but it can change the
    // tokenizer state represented by the checkpoint at byte 100.
    cache.rebaseAfterEdit(2, 2, 2, 40, 42, 2, {{40, 42}});
    assert(cache.size() == 0);
}

void testCheckpointDependencyKeepsLaterUnrelatedEdits() {
    NativeHighlightCache cache;
    cache.put(key(1, 140, 180), value(150, 160), dependencies(
        100, 200, static_cast<uint8_t>(HighlightSource::REGEX_STATE),
        static_cast<uint8_t>(HIGHLIGHT_DEPENDENCY_CHECKPOINT_BASED |
            HIGHLIGHT_DEPENDENCY_BOUNDARY_SENSITIVE)));
    cache.rebaseAfterEdit(2, 2, 2, 240, 242, 2, {{240, 242}});
    assert(cache.find(key(2, 140, 180)) != nullptr);
}

void testSharedBudgetReleasedOnEviction() {
    auto budget = std::make_shared<SharedNativeMemoryBudget>(1024u * 1024u);
    NativeHighlightCache cache(1024u * 1024u, budget);
    cache.put(key(1, 100, 200), value(120, 130), dependencies(110, 150));
    assert(budget->usedBytes() != 0);
    cache.rebaseAfterEdit(2, 2, 2, 125, 128, 0, {{125, 125}});
    assert(cache.size() == 0 && budget->usedBytes() == 0);
}
}

int main() {
    testSimpleRootAnalyzer();
    testUnrelatedEditKeepsAndRebasesEntry();
    testEditInsideDependencyEvictsEntry();
    testChangedTreeRangeEvictsDependency();
    testMissingDependencyIsNeverReused();
    testInsertionAtDependencyStartShiftsSnapshot();
    testInsertionInsideDependencyEvictsSnapshot();
    testDocumentWideDependencyInvalidatesBoundaryInsertion();
    testRegexDependencyInvalidatesBoundaryInsertion();
    testCheckpointDependencyInvalidatesEarlierEdits();
    testCheckpointDependencyKeepsLaterUnrelatedEdits();
    testSharedBudgetReleasedOnEviction();
    return 0;
}
