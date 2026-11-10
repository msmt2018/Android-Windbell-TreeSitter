#include "TextSource.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <utility>

namespace windbell::largefile {

bool copyTextRange(const TextSource &source, uint64_t offset,
                   uint64_t length, void *destination) noexcept {
    if ((offset & 1u) || (length & 1u) ||
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
    if (!valid_ || (offset & 1u) != 0 || maxBytes < 2u) return false;
    if (offset == total_bytes_) return true;
    if (offset > total_bytes_) return false;

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
    : data_(static_cast<const char *>(data)), size_bytes_(sizeBytes) {}

uint64_t ContiguousTextSource::sizeBytes() const noexcept {
    return size_bytes_;
}

bool ContiguousTextSource::contiguousData(
    const char **data, uint64_t *sizeBytes
) const noexcept {
    if (!data || !sizeBytes || (size_bytes_ != 0 && !data_)) return false;
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
