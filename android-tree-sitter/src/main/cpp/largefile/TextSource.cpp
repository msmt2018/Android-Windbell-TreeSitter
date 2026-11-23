#include "TextSource.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <utility>

namespace windbell::largefile {

bool copyTextRange(const TextSource &source, uint64_t offset,
                   uint64_t length, void *destination) noexcept {
    if ((source.sizeBytes() & 1u) || (offset & 1u) || (length & 1u) ||
        offset > source.sizeBytes() || length > source.sizeBytes() - offset ||
        (length != 0 && !destination) ||
        length > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) return false;
    auto *output = static_cast<uint8_t *>(destination);
    uint64_t copied = 0;
    while (copied < length) {
        TextChunkView chunk{};
        const uint32_t request = static_cast<uint32_t>(std::min<uint64_t>(
            kTreeSitterInputChunkBytes, length - copied));
        if (!source.read(offset + copied, request, &chunk) ||
            !chunk.data || chunk.size == 0 || (chunk.size & 1u) != 0 ||
            chunk.size > length - copied) return false;
        std::memcpy(output + static_cast<size_t>(copied), chunk.data, chunk.size);
        copied += chunk.size;
    }
    return true;
}

bool checkUtf16SurrogateBoundary(const TextSource &source,
                                 uint64_t byteOffset,
                                 bool *splitsPair) noexcept {
    if (!splitsPair || (source.sizeBytes() & 1u) != 0 ||
        (byteOffset & 1u) != 0 || byteOffset > source.sizeBytes()) return false;
    *splitsPair = false;
    if (byteOffset == 0 || byteOffset >= source.sizeBytes()) return true;

    uint8_t adjacent[4] = {};
    if (!copyTextRange(source, byteOffset - 2u, sizeof(adjacent), adjacent)) return false;
    const uint16_t before = static_cast<uint16_t>(adjacent[0]) |
        (static_cast<uint16_t>(adjacent[1]) << 8u);
    const uint16_t after = static_cast<uint16_t>(adjacent[2]) |
        (static_cast<uint16_t>(adjacent[3]) << 8u);
    *splitsPair = before >= 0xD800u && before <= 0xDBFFu &&
        after >= 0xDC00u && after <= 0xDFFFu;
    return true;
}


bool copyTreeSitterInputChunk(
    const TextSource &source,
    uint64_t offset,
    void *destination,
    uint32_t destinationCapacity,
    uint32_t *bytesRead
) noexcept {
    if (!bytesRead) return false;
    *bytesRead = 0;
    const uint64_t documentSize = source.sizeBytes();
    if ((documentSize & 1u) != 0 || (offset & 1u) != 0 || offset > documentSize) return false;
    if (offset == documentSize) return true;
    if (!destination || destinationCapacity < 2u) return false;

    // A source view is only valid until its next read(). Boundary inspection
    // below may cross a PieceTree boundary and switch a FileWindowSource's
    // backing window, so never return the borrowed view directly to Tree-sitter.
    TextChunkView chunk{};
    if (!source.read(offset, kTreeSitterInputChunkBytes, &chunk) ||
        !chunk.data || chunk.size == 0 || (chunk.size & 1u) != 0 ||
        chunk.size > kTreeSitterInputChunkBytes ||
        chunk.size > documentSize - offset) return false;

    bool splitsSurrogatePair = false;
    const uint64_t chunkEnd = offset + chunk.size;
    if (!checkUtf16SurrogateBoundary(source, chunkEnd, &splitsSurrogatePair)) return false;

    const uint64_t stableSize = static_cast<uint64_t>(chunk.size) +
        (splitsSurrogatePair ? 2u : 0u);
    if (stableSize > destinationCapacity || stableSize > documentSize - offset ||
        stableSize > std::numeric_limits<uint32_t>::max()) return false;

    // Re-read after all look-ahead operations. copyTextRange copies each
    // borrowed segment before requesting the next one, preserving correctness
    // even when adjacent pieces map to unrelated FileWindowSource offsets.
    if (!copyTextRange(source, offset, stableSize, destination)) return false;
    *bytesRead = static_cast<uint32_t>(stableSize);
    return true;
}

SegmentedTextSource::SegmentedTextSource(
    std::vector<TextSegment> segments,
    uint64_t totalBytes
) noexcept : segments_(std::move(segments)), total_bytes_(totalBytes) {
    uint64_t accumulated = 0;
    try {
        segment_starts_.reserve(segments_.size());
        for (const auto &segment : segments_) {
            if ((segment.size & 1u) != 0 || (segment.size != 0 && segment.data == nullptr) ||
                segment.size > std::numeric_limits<uint64_t>::max() - accumulated) {
                segment_starts_.clear();
                return;
            }
            segment_starts_.push_back(accumulated);
            accumulated += segment.size;
        }
    } catch (...) {
        segment_starts_.clear();
        return;
    }
    valid_ = accumulated == total_bytes_ && (total_bytes_ & 1u) == 0;
}

uint64_t SegmentedTextSource::sizeBytes() const noexcept {
    return total_bytes_;
}

bool SegmentedTextSource::isValid() const noexcept {
    return valid_;
}

bool SegmentedTextSource::read(
    uint64_t offset,
    uint32_t maxBytes,
    TextChunkView *out
) const noexcept {
    if (out == nullptr) return false;
    out->data = nullptr;
    out->size = 0;
    if (!valid_ || (offset & 1u) != 0 || offset > total_bytes_) return false;
    // EOF is a successful empty read regardless of the requested chunk size,
    // matching ContiguousTextSource and the TextSource callback contract.
    if (offset == total_bytes_) return true;
    if (maxBytes < 2u) return false;

    const auto it = std::upper_bound(segment_starts_.begin(), segment_starts_.end(), offset);
    if (it == segment_starts_.begin()) return false;
    const size_t index = static_cast<size_t>((it - segment_starts_.begin()) - 1);
    const auto &segment = segments_[index];
    const uint64_t local = offset - segment_starts_[index];
    if (local >= segment.size || local > static_cast<uint64_t>(
            std::numeric_limits<size_t>::max())) return false;

    const uint64_t available = segment.size - local;
    uint64_t count = std::min<uint64_t>(available, maxBytes);
    count &= ~uint64_t{1};
    if (count == 0) return false;
    out->data = segment.data + static_cast<size_t>(local);
    out->size = static_cast<uint32_t>(count);
    return true;
}

ContiguousTextSource::ContiguousTextSource(const void *data, uint64_t sizeBytes) noexcept
    : data_(static_cast<const char *>(data)), size_bytes_(sizeBytes),
      valid_((sizeBytes & 1u) == 0 && (sizeBytes == 0 || data != nullptr)) {}

bool ContiguousTextSource::isValid() const noexcept {
    return valid_;
}

uint64_t ContiguousTextSource::sizeBytes() const noexcept {
    return size_bytes_;
}

bool ContiguousTextSource::contiguousData(
    const char **data, uint64_t *sizeBytes
) const noexcept {
    if (!data || !sizeBytes || !valid_) return false;
    *data = data_;
    *sizeBytes = size_bytes_;
    return true;
}

bool ContiguousTextSource::read(
    uint64_t offset,
    uint32_t maxBytes,
    TextChunkView *out
) const noexcept {
    if (out == nullptr) return false;
    out->data = nullptr;
    out->size = 0;

    if (!valid_ || (offset & 1u) != 0 || offset > size_bytes_) return false;
    if (offset == size_bytes_) return true;
    if (maxBytes < 2u || data_ == nullptr) return false;

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
