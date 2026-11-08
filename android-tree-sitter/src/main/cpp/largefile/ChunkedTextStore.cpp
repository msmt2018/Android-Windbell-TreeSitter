#include "ChunkedTextStore.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <new>

namespace windbell::largefile {

ChunkedTextStore::ChunkedTextStore(uint64_t memoryLimitBytes, uint32_t chunkBytes) noexcept
    : memory_limit_bytes_(memoryLimitBytes),
      chunk_bytes_(std::max<uint32_t>(2u, chunkBytes & ~1u)) {}

bool ChunkedTextStore::append(const void *data, size_t length) noexcept {
    if (sealed_ || (length != 0 && data == nullptr) || (length & 1u) != 0) return false;
    if (length == 0) return true;

    const uint64_t amount = static_cast<uint64_t>(length);
    if (amount > std::numeric_limits<uint64_t>::max() - size_bytes_) return false;
    const auto *source = static_cast<const char *>(data);
    size_t consumed = 0;
    try {
        while (consumed < length) {
            const size_t chunk_index = static_cast<size_t>(size_bytes_ / chunk_bytes_);
            const size_t chunk_offset = static_cast<size_t>(size_bytes_ % chunk_bytes_);

            if (chunk_index == chunks_.size()) {
                if (chunk_bytes_ > memory_limit_bytes_ -
                    std::min(memory_limit_bytes_, allocated_bytes_)) {
                    return false;
                }
                auto chunk = std::unique_ptr<char[]>(new (std::nothrow) char[chunk_bytes_]);
                if (!chunk) return false;
                chunks_.push_back(std::move(chunk));
                allocated_bytes_ += chunk_bytes_;
            }

            const size_t writable = std::min<size_t>(
                length - consumed, chunk_bytes_ - chunk_offset);
            std::memcpy(chunks_[chunk_index].get() + chunk_offset, source + consumed, writable);
            consumed += writable;
            size_bytes_ += writable;
        }
    } catch (...) {
        // Keep size_bytes_ and allocated_bytes_ describing the successfully
        // appended prefix. The caller can discard this incomplete store.
        return false;
    }
    return true;
}

void ChunkedTextStore::seal() noexcept {
    sealed_ = true;
}

bool ChunkedTextStore::isSealed() const noexcept {
    return sealed_;
}

uint64_t ChunkedTextStore::sizeBytes() const noexcept {
    return size_bytes_;
}

uint64_t ChunkedTextStore::allocatedBytes() const noexcept {
    return allocated_bytes_;
}

bool ChunkedTextStore::read(
    uint64_t offset,
    uint32_t maxBytes,
    TextChunkView *out
) const noexcept {
    if (out == nullptr) return false;
    out->data = nullptr;
    out->size = 0;

    if (!sealed_) return false;
    if ((offset & 1u) != 0) return false;
    if (offset == size_bytes_) return true;
    if (offset > size_bytes_ || maxBytes == 0 || chunks_.empty()) return false;

    const uint64_t chunk_index_64 = offset / chunk_bytes_;
    if (chunk_index_64 >= chunks_.size()) return false;
    const size_t chunk_index = static_cast<size_t>(chunk_index_64);
    const size_t chunk_offset = static_cast<size_t>(offset % chunk_bytes_);
    const uint64_t remaining_in_chunk = chunk_bytes_ - chunk_offset;
    const uint64_t remaining_in_store = size_bytes_ - offset;
    const uint64_t count = std::min<uint64_t>(
        std::min<uint64_t>(remaining_in_chunk, remaining_in_store), maxBytes);

    const uint64_t aligned_count = count & ~uint64_t{1};
    if (aligned_count == 0 || aligned_count > std::numeric_limits<uint32_t>::max()) return false;
    out->data = chunks_[chunk_index].get() + chunk_offset;
    out->size = static_cast<uint32_t>(aligned_count);
    return true;
}

} // namespace windbell::largefile
