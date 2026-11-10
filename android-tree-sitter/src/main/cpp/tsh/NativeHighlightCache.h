#pragma once

#include "WindbellTshCore.h"

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
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
        touch(&found->second);
        return &found->second.value;
    }

    void put(Key key, Value value) {
        constexpr size_t kEntryOverhead =
            sizeof(Value) + sizeof(Key) + 5u * sizeof(void *);
        if (value.spans.capacity() >
            (static_cast<size_t>(-1) - kEntryOverhead) / sizeof(HighlightSpan)) return;
        const size_t bytes =
            value.spans.capacity() * sizeof(HighlightSpan) + kEntryOverhead;
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

    // Rebase cached viewport results across one committed text edit. Entries
    // intersecting the edit or any Tree-sitter changed range are discarded;
    // unaffected entries keep their spans and receive the new document versions.
    // If rebuilding the bounded cache fails, clear it rather than expose stale data.
    void rebaseAfterEdit(
        uint64_t newVersion, uint64_t newSourceVersion, uint64_t newPartitionVersion,
        uint32_t editStart, uint32_t oldEditEnd, uint32_t insertedBytes,
        const std::vector<std::pair<uint32_t, uint32_t>> &dirtyRanges
    ) noexcept {
        // A Tree-sitter query result may depend on syntax outside every emitted
        // capture (ancestors, siblings, predicates, and surrounding structure).
        // Capture/viewport overlap therefore cannot prove that an entry is still
        // valid after an edit. Until query execution records a conservative
        // dependency envelope per cache entry, invalidate on every edit.
        // This trades cache hit rate for correctness rather than exposing stale
        // syntax captures under a new document version.
        (void)newVersion;
        (void)newSourceVersion;
        (void)newPartitionVersion;
        (void)editStart;
        (void)oldEditEnd;
        (void)insertedBytes;
        (void)dirtyRanges;
        clear();
    }

    void clear() noexcept {
        if (shared_budget_ && used_bytes_ != 0) shared_budget_->release(used_bytes_);
        entries_.clear();
        head_ = nullptr;
        tail_ = nullptr;
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
};

} // namespace windbell::tsh
