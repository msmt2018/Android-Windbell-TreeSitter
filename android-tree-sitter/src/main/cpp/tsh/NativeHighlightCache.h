#pragma once

#include "WindbellTshCore.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <list>
#include <unordered_map>
#include <utility>
#include <vector>

namespace windbell::tsh {

/**
 * Per-document bounded viewport result cache.
 *
 * Keys include the document version and the exact UTF-16LE viewport. The
 * caller serializes access with its document lock. Entries are immutable
 * snapshots; edits advance the version, so stale results can never be reused.
 */
class NativeHighlightCache final {
public:
    struct Key {
        uint64_t version = 0;
        uint32_t start_byte = 0;
        uint32_t end_byte = 0;
        bool diagnostics = false;

        bool operator==(const Key &other) const noexcept {
            return version == other.version && start_byte == other.start_byte &&
                end_byte == other.end_byte && diagnostics == other.diagnostics;
        }
    };

    struct Value {
        std::vector<HighlightSpan> spans;
        uint32_t match_counts[4] = {};
    };

    explicit NativeHighlightCache(size_t budgetBytes = 8u * 1024u * 1024u)
        : budget_bytes_(budgetBytes) {}

    bool get(const Key &key, Value *out) {
        if (!out) return false;
        auto found = entries_.find(key);
        if (found == entries_.end()) return false;
        lru_.splice(lru_.begin(), lru_, found->second.position);
        *out = found->second.value;
        return true;
    }

    void put(Key key, Value value) {
        const size_t bytes = value.spans.size() * sizeof(HighlightSpan) + sizeof(Value);
        if (budget_bytes_ == 0 || bytes > budget_bytes_) return;
        auto existing = entries_.find(key);
        if (existing != entries_.end()) erase(existing);

        lru_.push_front(key);
        entries_.emplace(key, Entry{std::move(value), lru_.begin(), bytes});
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

    void clear() noexcept {
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
            uint64_t value = key.version;
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
        used_bytes_ -= std::min(used_bytes_, it->second.bytes);
        lru_.erase(it->second.position);
        entries_.erase(it);
    }

    size_t budget_bytes_;
    size_t used_bytes_ = 0;
    std::list<Key> lru_;
    std::unordered_map<Key, Entry, KeyHash> entries_;
};

} // namespace windbell::tsh
