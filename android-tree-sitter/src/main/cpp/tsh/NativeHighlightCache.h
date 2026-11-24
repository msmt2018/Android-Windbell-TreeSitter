#pragma once

#include "WindbellTshCore.h"

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <list>
#include <unordered_map>
#include <utility>
#include <vector>

namespace windbell::tsh {

class SharedNativeMemoryBudget final {
public:
    explicit SharedNativeMemoryBudget(size_t limitBytes) noexcept : limit_bytes_(limitBytes) {}

    bool tryAcquire(size_t bytes) noexcept {
        size_t current = used_bytes_.load(std::memory_order_relaxed);
        for (;;) {
            if (bytes > limit_bytes_ - std::min(limit_bytes_, current)) return false;
            if (used_bytes_.compare_exchange_weak(
                    current, current + bytes,
                    std::memory_order_acq_rel, std::memory_order_relaxed)) return true;
        }
    }

    void release(size_t bytes) noexcept {
        size_t current = used_bytes_.load(std::memory_order_relaxed);
        for (;;) {
            const size_t amount = std::min(current, bytes);
            if (used_bytes_.compare_exchange_weak(
                    current, current - amount,
                    std::memory_order_acq_rel, std::memory_order_relaxed)) return;
        }
    }

    size_t usedBytes() const noexcept { return used_bytes_.load(std::memory_order_relaxed); }
    size_t limitBytes() const noexcept { return limit_bytes_; }

private:
    const size_t limit_bytes_;
    std::atomic<size_t> used_bytes_{0};
};

/**
 * Per-document bounded native highlight result cache.
 *
 * Keys include document/source/partition/grammar versions and the exact
 * UTF-16LE viewport. Immutable snapshots carry explicit dependency envelopes;
 * edits rebase only snapshots whose captures and dependencies remain valid.
 */
class NativeHighlightCache final {
public:
    struct Key {
        uint64_t version = 0;
        uint64_t source_version = 0;
        uint64_t partition_version = 0;
        uint64_t grammar_revision = 0;
        uint32_t start_byte = 0;
        uint32_t end_byte = 0;
        bool diagnostics = false;

        bool operator==(const Key &other) const noexcept {
            return version == other.version && source_version == other.source_version &&
                partition_version == other.partition_version && grammar_revision == other.grammar_revision &&
                start_byte == other.start_byte &&
                end_byte == other.end_byte && diagnostics == other.diagnostics;
        }
    };

    using DependencyRange = HighlightDependencyRange;

    struct Value {
        std::vector<HighlightSpan> spans;
        // Absolute UTF-16LE byte ranges whose source text/tree structure may
        // influence this viewport result. Unknown dependencies are represented
        // explicitly as [0, document_size), never inferred from capture spans.
        std::vector<DependencyRange> dependencies;
        uint32_t match_counts[4] = {};
    };

    explicit NativeHighlightCache(
        size_t budgetBytes = 8u * 1024u * 1024u,
        std::shared_ptr<SharedNativeMemoryBudget> sharedBudget = nullptr)
        : budget_bytes_(budgetBytes), shared_budget_(std::move(sharedBudget)) {}

    ~NativeHighlightCache() { clear(); }

    NativeHighlightCache(const NativeHighlightCache &) = delete;
    NativeHighlightCache &operator=(const NativeHighlightCache &) = delete;

    // The document owner serializes cache access. Returning an immutable
    // entry avoids copying potentially millions of spans on every cache hit.
    // The pointer remains valid until the next mutating cache operation.
    const Value *find(const Key &key) noexcept {
        auto found = entries_.find(key);
        if (found == entries_.end()) return nullptr;
        touch(&found->second);
        return &found->second.value;
    }

    // Snapshot viewport keys whose dependency metadata survived edit rebasing.
    // This ledger is deliberately independent from the span-payload LRU: Kotlin
    // may still own a compact capture page after the native payload was evicted.
    std::vector<std::pair<uint32_t, uint32_t>> snapshotRanges(
        bool diagnostics
    ) const {
        std::vector<std::pair<uint32_t, uint32_t>> ranges;
        ranges.reserve(dependency_entries_.size());
        for (const auto& item : dependency_entries_) {
            if (item.first.diagnostics == diagnostics) {
                ranges.emplace_back(item.first.start_byte, item.first.end_byte);
            }
        }
        std::sort(ranges.begin(), ranges.end());
        ranges.erase(std::unique(ranges.begin(), ranges.end()), ranges.end());
        return ranges;
    }

    void put(Key key, Value value) {
        // Preserve dependency proof even when the larger native span payload
        // cannot enter the LRU. The Kotlin capture cache may still retain that
        // immutable result and must not lose edit-reuse eligibility just because
        // this second payload copy was evicted.
        rememberDependencies(key, value.dependencies);
        constexpr size_t kEntryOverhead =
            sizeof(Value) + sizeof(Key) + 5u * sizeof(void *);
        const size_t span_capacity = value.spans.capacity();
        const size_t dependency_capacity = value.dependencies.capacity();
        if (span_capacity > (static_cast<size_t>(-1) - kEntryOverhead) /
                sizeof(HighlightSpan)) return;
        const size_t span_bytes = span_capacity * sizeof(HighlightSpan);
        if (dependency_capacity > (static_cast<size_t>(-1) - kEntryOverhead - span_bytes) /
                sizeof(DependencyRange)) return;
        const size_t bytes = span_bytes +
            dependency_capacity * sizeof(DependencyRange) + kEntryOverhead;
        if (budget_bytes_ == 0 || bytes > budget_bytes_) return;
        auto existing = entries_.find(key);
        if (existing != entries_.end()) erase(existing);
        if (shared_budget_ && !shared_budget_->tryAcquire(bytes)) return;

        try {
            const auto inserted = entries_.emplace(
                key, Entry{std::move(value), nullptr, nullptr, nullptr, bytes});
            if (!inserted.second) {
                if (shared_budget_) shared_budget_->release(bytes);
                return;
            }
            inserted.first->second.key = &inserted.first->first;
            linkFront(&inserted.first->second);
        } catch (...) {
            if (shared_budget_) shared_budget_->release(bytes);
            return;
        }
        used_bytes_ += bytes;
        while (used_bytes_ > budget_bytes_ && tail_ != nullptr) {
            const auto victim = entries_.find(*tail_->key);
            if (victim == entries_.end()) {
                unlink(tail_);
                continue;
            }
            erase(victim);
        }
    }

    // Rebase immutable viewport snapshots into the new document coordinate
    // space. Every producer must attach its dependency envelope. Missing
    // dependency metadata is treated as document-wide and therefore evicted.
    // dirtyRanges are Tree-sitter changed ranges in POST-edit coordinates.
    void rebaseAfterEdit(
        uint64_t newVersion, uint64_t newSourceVersion, uint64_t newPartitionVersion,
        uint32_t editStart, uint32_t oldEditEnd, uint32_t insertedBytes,
        const std::vector<std::pair<uint32_t, uint32_t>> &dirtyRanges
    ) noexcept {
        if (oldEditEnd < editStart) {
            clear();
            return;
        }
        const int64_t delta = static_cast<int64_t>(insertedBytes) -
            static_cast<int64_t>(oldEditEnd - editStart);
        const uint64_t insertedEnd64 = static_cast<uint64_t>(editStart) + insertedBytes;
        if (insertedEnd64 > UINT32_MAX) {
            clear();
            return;
        }
        const uint32_t insertedEnd = static_cast<uint32_t>(insertedEnd64);

        auto overlaps = [](uint32_t a0, uint32_t a1, uint32_t b0, uint32_t b1) noexcept {
            return a0 < b1 && b0 < a1;
        };
        auto transformRange = [&](uint32_t *start, uint32_t *end) noexcept -> bool {
            if (*start > *end) return false;
            // A dependency/capture crossing replaced text, or containing an
            // insertion point, cannot be proven reusable.
            if (oldEditEnd > editStart && overlaps(*start, *end, editStart, oldEditEnd))
                return false;
            if (oldEditEnd == editStart && *start < editStart && *end > editStart)
                return false;
            if (*end <= editStart) return true;
            if (*start >= oldEditEnd) {
                const int64_t shiftedStart = static_cast<int64_t>(*start) + delta;
                const int64_t shiftedEnd = static_cast<int64_t>(*end) + delta;
                if (shiftedStart < 0 || shiftedEnd < shiftedStart ||
                    shiftedEnd > UINT32_MAX) return false;
                *start = static_cast<uint32_t>(shiftedStart);
                *end = static_cast<uint32_t>(shiftedEnd);
                return true;
            }
            return false;
        };

        std::unordered_map<Key, DependencyEntry, KeyHash> rebased_dependencies;
        std::list<Key> rebased_dependency_lru;
        size_t rebased_dependency_bytes = 0;
        std::unordered_map<Key, Entry, KeyHash> rebased;
        try {
            // Rebase the compact dependency ledger independently of the span
            // payload LRU. It contains every admitted viewport proof, including
            // proofs whose native span vectors have already been evicted.
            rebased_dependencies.reserve(dependency_entries_.size());
            for (const Key& old_key : dependency_lru_) {
                auto found = dependency_entries_.find(old_key);
                if (found == dependency_entries_.end()) continue;
                Key next_key = old_key;
                auto dependencies = found->second.ranges;
                bool reusable = !dependencies.empty();
                reusable = reusable && transformRange(
                    &next_key.start_byte, &next_key.end_byte);
                for (auto &dependency : dependencies) {
                    if (!reusable) break;
                    if ((dependency.flags & HIGHLIGHT_DEPENDENCY_DOCUMENT_WIDE) != 0) {
                        reusable = false;
                        break;
                    }
                    if ((dependency.flags & HIGHLIGHT_DEPENDENCY_CHECKPOINT_BASED) != 0 &&
                        editStart <= dependency.start_byte) {
                        reusable = false;
                        break;
                    }
                    if ((dependency.flags & HIGHLIGHT_DEPENDENCY_BOUNDARY_SENSITIVE) != 0 &&
                        editStart <= dependency.end_byte &&
                        oldEditEnd >= dependency.start_byte) {
                        reusable = false;
                        break;
                    }
                    reusable = transformRange(
                        &dependency.start_byte, &dependency.end_byte);
                    if (!reusable) break;
                    if (insertedBytes != 0 &&
                        overlaps(dependency.start_byte, dependency.end_byte,
                                 editStart, insertedEnd)) {
                        reusable = false;
                        break;
                    }
                    for (const auto &dirty : dirtyRanges) {
                        if (dirty.first < dirty.second &&
                            overlaps(dependency.start_byte, dependency.end_byte,
                                     dirty.first, dirty.second)) {
                            reusable = false;
                            break;
                        }
                    }
                }
                if (!reusable) continue;
                next_key.version = newVersion;
                next_key.source_version = newSourceVersion;
                next_key.partition_version = newPartitionVersion;
                const size_t bytes = dependencyWeight(dependencies);
                if (bytes > kDependencyLedgerBudgetBytes ||
                    bytes > kDependencyLedgerBudgetBytes - rebased_dependency_bytes) {
                    continue;
                }
                rebased_dependency_lru.push_back(next_key);
                auto lru = std::prev(rebased_dependency_lru.end());
                auto inserted = rebased_dependencies.emplace(
                    next_key, DependencyEntry{std::move(dependencies), bytes, lru});
                if (inserted.second) rebased_dependency_bytes += bytes;
                else rebased_dependency_lru.erase(lru);
            }
            dependency_entries_.swap(rebased_dependencies);
            dependency_lru_.swap(rebased_dependency_lru);
            dependency_used_bytes_ = rebased_dependency_bytes;

            rebased.reserve(entries_.size());
            Entry *entry = head_;
            while (entry) {
                Entry *next = entry->next;
                auto found = entries_.find(*entry->key);
                if (found == entries_.end()) {
                    unlink(entry);
                    entry = next;
                    continue;
                }

                Key next_key = found->first;
                bool reusable = !entry->value.dependencies.empty();
                // The requested viewport itself must not include replaced text.
                reusable = reusable && transformRange(
                    &next_key.start_byte, &next_key.end_byte);

                for (auto &dependency : entry->value.dependencies) {
                    if (!reusable) break;
                    // Document-context dependencies cannot be rebased across
                    // any edit, including insertion exactly at byte zero where
                    // ordinary interval shifting would otherwise skip new text.
                    if ((dependency.flags & HIGHLIGHT_DEPENDENCY_DOCUMENT_WIDE) != 0) {
                        reusable = false;
                        break;
                    }
                    // Stateful Regex dependencies are based on a tokenizer
                    // checkpoint. Any edit at/before that checkpoint may change
                    // the state summarized by it, even when the edit is outside
                    // the recorded scan interval.
                    if ((dependency.flags & HIGHLIGHT_DEPENDENCY_CHECKPOINT_BASED) != 0 &&
                        editStart <= dependency.start_byte) {
                        reusable = false;
                        break;
                    }
                    // Bounded Regex can match across either edge of its scan
                    // window. An insertion at the exact edge changes that
                    // window's context even when the old interval can be shifted.
                    if ((dependency.flags & HIGHLIGHT_DEPENDENCY_BOUNDARY_SENSITIVE) != 0 &&
                        editStart <= dependency.end_byte &&
                        oldEditEnd >= dependency.start_byte) {
                        reusable = false;
                        break;
                    }
                    reusable = transformRange(
                        &dependency.start_byte, &dependency.end_byte);
                    if (!reusable) break;
                    if (insertedBytes != 0 &&
                        overlaps(dependency.start_byte, dependency.end_byte,
                                 editStart, insertedEnd)) {
                        reusable = false;
                        break;
                    }
                    for (const auto &dirty : dirtyRanges) {
                        if (dirty.first < dirty.second &&
                            overlaps(dependency.start_byte, dependency.end_byte,
                                     dirty.first, dirty.second)) {
                            reusable = false;
                            break;
                        }
                    }
                }

                for (auto &span : entry->value.spans) {
                    if (!reusable) break;
                    reusable = transformRange(&span.start_byte, &span.end_byte);
                }

                if (!reusable) {
                    erase(found);
                    entry = next;
                    continue;
                }

                next_key.version = newVersion;
                next_key.source_version = newSourceVersion;
                next_key.partition_version = newPartitionVersion;
                auto node = entries_.extract(found);
                node.key() = next_key;
                auto inserted = rebased.insert(std::move(node));
                if (!inserted.inserted) {
                    Entry *duplicate = &inserted.position->second;
                    const size_t released = std::min(used_bytes_, entry->bytes);
                    used_bytes_ -= released;
                    if (shared_budget_) shared_budget_->release(released);
                    unlink(entry);
                    (void)duplicate;
                } else {
                    inserted.position->second.key = &inserted.position->first;
                }
                entry = next;
            }
            entries_.swap(rebased);
        } catch (...) {
            // A partially rebased cache is never published. Cache admission is
            // optional; dropping it is safer than returning mixed-version data.
            clear();
        }
    }

    void clear() noexcept {
        if (shared_budget_ && used_bytes_ != 0) shared_budget_->release(used_bytes_);
        entries_.clear();
        head_ = nullptr;
        tail_ = nullptr;
        used_bytes_ = 0;
        dependency_entries_.clear();
        dependency_lru_.clear();
        dependency_used_bytes_ = 0;
    }

    size_t usedBytes() const noexcept { return used_bytes_; }
    size_t dependencyBytesUsed() const noexcept { return dependency_used_bytes_; }
    size_t budgetBytes() const noexcept { return budget_bytes_; }
    size_t size() const noexcept { return entries_.size(); }

private:
    struct KeyHash {
        size_t operator()(const Key &key) const noexcept {
            uint64_t value = key.version ^ (key.source_version * 0x9E3779B97F4A7C15ULL) ^
                (key.partition_version * 0xBF58476D1CE4E5B9ULL);
            value ^= key.grammar_revision * 0xD6E8FEB86659FD93ULL;
            value ^= static_cast<uint64_t>(key.start_byte) << 17u;
            value ^= static_cast<uint64_t>(key.end_byte) << 1u;
            value ^= key.diagnostics ? 0x9E3779B97F4A7C15ULL : 0ULL;
            value ^= value >> 30u;
            value *= 0xBF58476D1CE4E5B9ULL;
            value ^= value >> 27u;
            value *= 0x94D049BB133111EBULL;
            value ^= value >> 31u;
            return static_cast<size_t>(value);
        }
    };

    struct Entry {
        Value value;
        const Key *key = nullptr;
        Entry *previous = nullptr;
        Entry *next = nullptr;
        size_t bytes = 0;
    };

    struct DependencyEntry {
        std::vector<DependencyRange> ranges;
        size_t bytes = 0;
        std::list<Key>::iterator lru;
    };

    static constexpr size_t kDependencyLedgerBudgetBytes = 2u * 1024u * 1024u;
    static constexpr size_t kDependencyLedgerMaxEntries = 32768u;

    static size_t dependencyWeight(
        const std::vector<DependencyRange>& ranges
    ) noexcept {
        constexpr size_t overhead = sizeof(Key) + sizeof(DependencyEntry) + 48u;
        if (ranges.capacity() >
            (static_cast<size_t>(-1) - overhead) / sizeof(DependencyRange)) {
            return static_cast<size_t>(-1);
        }
        return overhead + ranges.capacity() * sizeof(DependencyRange);
    }

    void rememberDependencies(
        const Key& key,
        const std::vector<DependencyRange>& dependencies
    ) noexcept {
        if (dependencies.empty()) return;
        try {
            std::vector<DependencyRange> copy = dependencies;
            const size_t bytes = dependencyWeight(copy);
            if (bytes > kDependencyLedgerBudgetBytes) return;

            auto existing = dependency_entries_.find(key);
            if (existing != dependency_entries_.end()) {
                dependency_used_bytes_ -= std::min(
                    dependency_used_bytes_, existing->second.bytes);
                dependency_lru_.erase(existing->second.lru);
                dependency_entries_.erase(existing);
            }
            while ((dependency_used_bytes_ > kDependencyLedgerBudgetBytes - bytes ||
                    dependency_entries_.size() >= kDependencyLedgerMaxEntries) &&
                   !dependency_lru_.empty()) {
                const Key oldest = dependency_lru_.front();
                dependency_lru_.pop_front();
                auto victim = dependency_entries_.find(oldest);
                if (victim == dependency_entries_.end()) continue;
                dependency_used_bytes_ -= std::min(
                    dependency_used_bytes_, victim->second.bytes);
                dependency_entries_.erase(victim);
            }

            dependency_lru_.push_back(key);
            auto lru = std::prev(dependency_lru_.end());
            try {
                auto inserted = dependency_entries_.emplace(
                    key, DependencyEntry{std::move(copy), bytes, lru});
                if (!inserted.second) {
                    dependency_lru_.erase(lru);
                    return;
                }
            } catch (...) {
                dependency_lru_.erase(lru);
                throw;
            }
            dependency_used_bytes_ += bytes;
        } catch (...) {
            // Dependency metadata is an optimization. If it cannot be retained,
            // the range will be conservatively invalidated on the next edit.
        }
    }

    using Iterator = typename std::unordered_map<Key, Entry, KeyHash>::iterator;

    void unlink(Entry *entry) noexcept {
        if (entry->previous) entry->previous->next = entry->next;
        else head_ = entry->next;
        if (entry->next) entry->next->previous = entry->previous;
        else tail_ = entry->previous;
        entry->previous = nullptr;
        entry->next = nullptr;
    }

    void linkFront(Entry *entry) noexcept {
        entry->previous = nullptr;
        entry->next = head_;
        if (head_) head_->previous = entry;
        else tail_ = entry;
        head_ = entry;
    }

    void touch(Entry *entry) noexcept {
        if (entry == head_) return;
        unlink(entry);
        linkFront(entry);
    }

    void erase(Iterator it) noexcept {
        Entry *entry = &it->second;
        const size_t released = std::min(used_bytes_, entry->bytes);
        used_bytes_ -= released;
        if (shared_budget_) shared_budget_->release(released);
        unlink(entry);
        entries_.erase(it);
    }

    size_t budget_bytes_;
    size_t used_bytes_ = 0;
    std::shared_ptr<SharedNativeMemoryBudget> shared_budget_;
    Entry *head_ = nullptr;
    Entry *tail_ = nullptr;
    std::unordered_map<Key, Entry, KeyHash> entries_;
    std::unordered_map<Key, DependencyEntry, KeyHash> dependency_entries_;
    std::list<Key> dependency_lru_;
    size_t dependency_used_bytes_ = 0;
};

} // namespace windbell::tsh
