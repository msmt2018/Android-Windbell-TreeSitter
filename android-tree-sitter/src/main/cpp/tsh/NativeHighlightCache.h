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
 * Keys include the document version and the exact UTF-16LE viewport. The
 * caller serializes access with its document lock. Entries are immutable
 * snapshots; edits advance the version, so stale results can never be reused.
 */
class NativeHighlightCache final {
public:
    struct Key {
        uint64_t version = 0;
        uint64_t source_version = 0;
        uint64_t partition_version = 0;
        uint32_t start_byte = 0;
        uint32_t end_byte = 0;
        bool diagnostics = false;

        bool operator==(const Key &other) const noexcept {
            return version == other.version && source_version == other.source_version &&
                partition_version == other.partition_version && start_byte == other.start_byte &&
                end_byte == other.end_byte && diagnostics == other.diagnostics;
        }
    };

    struct Value {
        std::vector<HighlightSpan> spans;
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
        lru_.splice(lru_.begin(), lru_, found->second.position);
        return &found->second.value;
    }

    void put(Key key, Value value) {
        constexpr size_t kEntryOverhead =
            sizeof(Value) + sizeof(Key) + 6u * sizeof(void *);
        if (value.spans.capacity() >
            (static_cast<size_t>(-1) - kEntryOverhead) / sizeof(HighlightSpan)) return;
        const size_t bytes =
            value.spans.capacity() * sizeof(HighlightSpan) + kEntryOverhead;
        if (budget_bytes_ == 0 || bytes > budget_bytes_) return;
        auto existing = entries_.find(key);
        if (existing != entries_.end()) erase(existing);
        if (shared_budget_ && !shared_budget_->tryAcquire(bytes)) return;

        try {
            lru_.push_front(key);
            try {
                const auto inserted = entries_.emplace(
                    key, Entry{std::move(value), lru_.begin(), bytes});
                if (!inserted.second) {
                    lru_.pop_front();
                    if (shared_budget_) shared_budget_->release(bytes);
                    return;
                }
            } catch (...) {
                lru_.pop_front();
                if (shared_budget_) shared_budget_->release(bytes);
                return;
            }
        } catch (...) {
            if (shared_budget_) shared_budget_->release(bytes);
            return;
        }
        used_bytes_ += bytes;
        while (used_bytes_ > budget_bytes_ && !lru_.empty()) {
            auto victim = entries_.find(lru_.back());
            if (victim == entries_.end()) {
                lru_.pop_back();
                continue;
            }
            erase(victim);
        }
    }

    // Rebase cached viewport results across one committed text edit. Entries
    // intersecting the edit or any Tree-sitter changed range are discarded;
    // unaffected entries keep their spans and receive the new document versions.
    // If rebuilding the bounded cache fails, clear it rather than expose stale data.
    void rebaseAfterEdit(
        uint64_t newVersion, uint64_t newSourceVersion, uint64_t newPartitionVersion,
        uint32_t editStart, uint32_t oldEditEnd, uint32_t insertedBytes,
        const std::vector<std::pair<uint32_t, uint32_t>> &dirtyRanges
    ) noexcept {
        const int64_t delta = static_cast<int64_t>(insertedBytes) -
            static_cast<int64_t>(oldEditEnd - editStart);
        std::list<Key> nextLru;
        std::unordered_map<Key, Entry, KeyHash> nextEntries;
        try {
            nextEntries.reserve(entries_.size());
            for (const Key &oldKey : lru_) {
                auto found = entries_.find(oldKey);
                if (found == entries_.end()) continue;
                Key key = oldKey;
                if (key.end_byte <= editStart) {
                    // Entire cached range is before the edit.
                } else if (key.start_byte >= oldEditEnd &&
                           !(editStart == oldEditEnd && key.start_byte < editStart)) {
                    const int64_t start = static_cast<int64_t>(key.start_byte) + delta;
                    const int64_t end = static_cast<int64_t>(key.end_byte) + delta;
                    if (start < 0 || end < start || end > UINT32_MAX) continue;
                    key.start_byte = static_cast<uint32_t>(start);
                    key.end_byte = static_cast<uint32_t>(end);
                } else {
                    continue; // The cached viewport overlaps replaced text.
                }
                bool intersectsDirty = false;
                for (const auto &range : dirtyRanges) {
                    if (key.start_byte < range.second && range.first < key.end_byte) {
                        intersectsDirty = true;
                        break;
                    }
                }
                if (intersectsDirty) continue;
                Value value = found->second.value;
                bool spansValid = true;
                for (auto &span : value.spans) {
                    if (span.end_byte <= editStart) continue;
                    if (span.start_byte >= oldEditEnd &&
                        !(editStart == oldEditEnd && span.start_byte < editStart)) {
                        const int64_t start = static_cast<int64_t>(span.start_byte) + delta;
                        const int64_t end = static_cast<int64_t>(span.end_byte) + delta;
                        if (start < 0 || end < start || end > UINT32_MAX) {
                            spansValid = false;
                            break;
                        }
                        span.start_byte = static_cast<uint32_t>(start);
                        span.end_byte = static_cast<uint32_t>(end);
                    } else {
                        spansValid = false;
                        break;
                    }
                }
                if (!spansValid) continue;
                key.version = newVersion;
                key.source_version = newSourceVersion;
                key.partition_version = newPartitionVersion;
                nextLru.push_back(key);
                auto position = std::prev(nextLru.end());
                nextEntries.emplace(key, Entry{std::move(value), position, found->second.bytes});
            }
        } catch (...) {
            clear();
            return;
        }
        entries_.swap(nextEntries);
        lru_.swap(nextLru);
        // Cache accounting is unchanged for retained entries only. Recompute it
        // because invalidated entries released their memory from this cache.
        size_t retainedBytes = 0;
        for (const auto &entry : entries_) {
            if (entry.second.bytes > static_cast<size_t>(-1) - retainedBytes) {
                clear();
                return;
            }
            retainedBytes += entry.second.bytes;
        }
        const size_t released = used_bytes_ - std::min(used_bytes_, retainedBytes);
        if (shared_budget_ && released) shared_budget_->release(released);
        used_bytes_ = retainedBytes;
    }

    void clear() noexcept {
        if (shared_budget_ && used_bytes_ != 0) shared_budget_->release(used_bytes_);
        entries_.clear();
        lru_.clear();
        used_bytes_ = 0;
    }

    size_t usedBytes() const noexcept { return used_bytes_; }
    size_t budgetBytes() const noexcept { return budget_bytes_; }
    size_t size() const noexcept { return entries_.size(); }

private:
    struct KeyHash {
        size_t operator()(const Key &key) const noexcept {
            uint64_t value = key.version ^ (key.source_version * 0x9E3779B97F4A7C15ULL) ^
                (key.partition_version * 0xBF58476D1CE4E5B9ULL);
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
        std::list<Key>::iterator position;
        size_t bytes = 0;
    };

    using Iterator = typename std::unordered_map<Key, Entry, KeyHash>::iterator;

    void erase(Iterator it) noexcept {
        const size_t released = std::min(used_bytes_, it->second.bytes);
        used_bytes_ -= released;
        if (shared_budget_) shared_budget_->release(released);
        lru_.erase(it->second.position);
        entries_.erase(it);
    }

    size_t budget_bytes_;
    size_t used_bytes_ = 0;
    std::shared_ptr<SharedNativeMemoryBudget> shared_budget_;
    std::list<Key> lru_;
    std::unordered_map<Key, Entry, KeyHash> entries_;
};

} // namespace windbell::tsh
