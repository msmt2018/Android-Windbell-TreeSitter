#include "TextSource.h"

#include <algorithm>
#include <limits>

namespace windbell::largefile {

ContiguousTextSource::ContiguousTextSource(const void *data, uint64_t sizeBytes) noexcept
    : data_(static_cast<const char *>(data)), size_bytes_(sizeBytes) {}

uint64_t ContiguousTextSource::sizeBytes() const noexcept {
    return size_bytes_;
}

bool ContiguousTextSource::read(
    uint64_t offset,
    uint32_t maxBytes,
    TextChunkView *out
) const noexcept {
    if (out == nullptr) return false;
    out->data = nullptr;
    out->size = 0;

    if (data_ == nullptr || maxBytes == 0 || offset >= size_bytes_) {
        return offset == size_bytes_;
    }

    const uint64_t remaining = size_bytes_ - offset;
    uint64_t count = std::min<uint64_t>(remaining, maxBytes);

    // This adapter serves UTF-16LE to Tree-sitter. Never split a UTF-16 code
    // unit across callback chunks, even though Tree-sitter accepts byte offsets.
    count &= ~uint64_t{1};
    if (count == 0) return false;
    if (offset > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) return false;

    out->data = data_ + static_cast<size_t>(offset);
    out->size = static_cast<uint32_t>(count);
    return true;
}

} // namespace windbell::largefile
