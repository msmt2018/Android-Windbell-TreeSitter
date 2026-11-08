/*
 * Native large-text input primitives for Windbell Tree-sitter.
 *
 * The abstraction deliberately uses 64-bit source offsets. Adapters to upstream
 * APIs with narrower offsets must enforce their limits at that boundary.
 */
#pragma once

#include <cstddef>
#include <cstdint>

namespace windbell::largefile {

inline constexpr uint32_t kTreeSitterInputChunkBytes = 64u * 1024u;

struct TextChunkView {
    const char *data = nullptr;
    uint32_t size = 0;
};

/**
 * Read-only, random-access byte source. Implementations must keep returned chunk
 * memory valid until the next read() call or until the source is destroyed.
 */
class TextSource {
public:
    virtual ~TextSource() = default;
    virtual uint64_t sizeBytes() const noexcept = 0;
    virtual bool read(uint64_t offset, uint32_t maxBytes, TextChunkView *out) const noexcept = 0;
};

/**
 * Zero-copy adapter for existing contiguous UTF-16LE storage.
 *
 * This is intentionally only an adapter, not the long-term storage
 * implementation. File-backed, chunked, piece-table and rope sources can
 * implement TextSource without changing the Tree-sitter input callback.
 */
class ContiguousTextSource final : public TextSource {
public:
    ContiguousTextSource(const void *data, uint64_t sizeBytes) noexcept;

    uint64_t sizeBytes() const noexcept override;
    bool read(uint64_t offset, uint32_t maxBytes, TextChunkView *out) const noexcept override;

private:
    const char *data_;
    uint64_t size_bytes_;
};

} // namespace windbell::largefile
