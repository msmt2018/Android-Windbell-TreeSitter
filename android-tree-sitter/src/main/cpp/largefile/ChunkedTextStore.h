/*
 * Bounded, append-only native text storage.
 *
 * Chunks are individually allocated so appending a large document never
 * requires reallocating or copying its already-ingested prefix. A sealed
 * instance can be exposed as a stable TextSource during parsing.
 */
#pragma once

#include "TextSource.h"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace windbell::largefile {

inline constexpr uint32_t kTextStoreChunkBytes = 64u * 1024u;

class ChunkedTextStore final : public TextSource {
public:
    explicit ChunkedTextStore(
        uint64_t memoryLimitBytes = UINT64_MAX,
        uint32_t chunkBytes = kTextStoreChunkBytes) noexcept;

    // Appends bytes. Returns false without throwing on invalid input,
    // budget exhaustion, or allocation failure.
    bool append(const void *data, size_t length) noexcept;

    // No further writes are accepted after seal(). Parsing should only use
    // sealed stores so all chunk views remain stable and thread-safe.
    void seal() noexcept;
    bool isSealed() const noexcept;

    uint64_t sizeBytes() const noexcept override;
    uint64_t allocatedBytes() const noexcept;
    bool read(uint64_t offset, uint32_t maxBytes, TextChunkView *out) const noexcept override;

private:
    std::vector<std::unique_ptr<char[]>> chunks_;
    uint64_t size_bytes_ = 0;
    uint64_t allocated_bytes_ = 0;
    uint64_t memory_limit_bytes_;
    uint32_t chunk_bytes_;
    bool sealed_ = false;
};

} // namespace windbell::largefile
