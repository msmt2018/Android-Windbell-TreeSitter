#include "TextPartitionIndex.h"

#include <algorithm>
#include <cstring>
#include <limits>

namespace windbell::largefile {

TextPartitionIndex::TextPartitionIndex(uint32_t strideLines) noexcept
    : stride_lines_(std::max<uint32_t>(1, strideLines)) {
    reset();
}

void TextPartitionIndex::reset() noexcept {
    checkpoints_.clear();
    checkpoints_.push_back({0, 0});
    ++version_;
}

void TextPartitionIndex::addCheckpoint(uint64_t byteOffset, uint64_t line) noexcept {
    if (!checkpoints_.empty() &&
        checkpoints_.back().byte_offset == byteOffset) return;
    try {
        checkpoints_.push_back({byteOffset, line});
    } catch (...) {
        // The index is an optimization. A failed checkpoint allocation leaves
        // the preceding valid sparse index usable.
    }
}

bool TextPartitionIndex::countNewlines(const TextSource &source,
                                       uint64_t startByte, uint64_t endByte,
                                       uint64_t *count) noexcept {
    if (!count || (startByte & 1u) || (endByte & 1u) ||
        endByte < startByte || endByte > source.sizeBytes()) return false;
    *count = 0;
    uint64_t offset = startByte;
    while (offset < endByte) {
        TextChunkView chunk{};
        const uint32_t request = static_cast<uint32_t>(std::min<uint64_t>(
            kTreeSitterInputChunkBytes, endByte - offset));
        if (!source.read(offset, request, &chunk) || !chunk.data || chunk.size == 0)
            return false;
        const auto *bytes = reinterpret_cast<const uint8_t *>(chunk.data);
        for (uint32_t i = 0; i + 1 < chunk.size; i += 2) {
            const uint16_t unit = static_cast<uint16_t>(bytes[i]) |
                (static_cast<uint16_t>(bytes[i + 1]) << 8u);
            if (unit == '\n') ++*count;
        }
        offset += chunk.size;
    }
    return true;
}

bool TextPartitionIndex::scan(const TextSource &source, uint64_t startByte,
                              uint64_t startLine, uint64_t targetByte,
                              uint64_t targetLine, bool stopByLine,
                              uint64_t *result) noexcept {
    if (!result || (startByte & 1u) || startByte > source.sizeBytes()) return false;
    uint64_t offset = startByte;
    uint64_t line = startLine;
    if (!stopByLine && targetByte < startByte) return false;
    while (offset < source.sizeBytes()) {
        if ((!stopByLine && offset >= targetByte) ||
            (stopByLine && line >= targetLine)) {
            *result = offset;
            return true;
        }
        TextChunkView chunk{};
        if (!source.read(offset, kTreeSitterInputChunkBytes, &chunk) ||
            !chunk.data || chunk.size == 0) return false;
        const auto *bytes = reinterpret_cast<const uint8_t *>(chunk.data);
        for (uint32_t i = 0; i + 1 < chunk.size; i += 2) {
            const uint16_t unit = static_cast<uint16_t>(bytes[i]) |
                (static_cast<uint16_t>(bytes[i + 1]) << 8u);
            offset += 2;
            if (unit == '\n') {
                ++line;
                if (line % stride_lines_ == 0) addCheckpoint(offset, line);
                if ((!stopByLine && offset >= targetByte) ||
                    (stopByLine && line >= targetLine)) {
                    *result = offset;
                    return true;
                }
            }
            if (!stopByLine && offset >= targetByte) {
                *result = offset;
                return true;
            }
        }
    }
    *result = offset;
    return !stopByLine || line >= targetLine;
}

bool TextPartitionIndex::lineForByte(const TextSource &source,
                                     uint64_t byteOffset,
                                     uint64_t *line) noexcept {
    if (!line || (byteOffset & 1u) || byteOffset > source.sizeBytes()) return false;
    auto it = std::upper_bound(checkpoints_.begin(), checkpoints_.end(), byteOffset,
        [](uint64_t value, const Checkpoint &checkpoint) {
            return value < checkpoint.byte_offset;
        });
    if (it != checkpoints_.begin()) --it;
    uint64_t ignored = 0;
    uint64_t result = 0;
    if (!scan(source, it->byte_offset, it->line, byteOffset, 0, false, &ignored))
        return false;
    // Count line feeds from the checkpoint to the exact target.
    if (!countNewlines(source, it->byte_offset, ignored, &result)) return false;
    *line = it->line + result;
    return true;
}

bool TextPartitionIndex::byteForLine(const TextSource &source,
                                     uint64_t requestedLine,
                                     uint64_t *byteOffset) noexcept {
    if (!byteOffset) return false;
    auto it = std::upper_bound(checkpoints_.begin(), checkpoints_.end(), requestedLine,
        [](uint64_t value, const Checkpoint &checkpoint) {
            return value < checkpoint.line;
        });
    if (it != checkpoints_.begin()) --it;
    return scan(source, it->byte_offset, it->line, 0, requestedLine, true, byteOffset);
}

bool TextPartitionIndex::applyEdit(uint64_t startByte, uint64_t oldEndByte,
                                   const void *inserted, uint64_t insertedBytes,
                                   uint64_t newSourceSize) noexcept {
    if ((startByte & 1u) || (oldEndByte & 1u) || (insertedBytes & 1u) ||
        oldEndByte < startByte || (insertedBytes && !inserted)) return false;
    uint64_t removedLines = 0;
    // The old source is not available here; caller supplies the removed newline
    // count through a prior lineForByte lookup in the document transaction.
    (void)removedLines;
    (void)newSourceSize;
    (void)inserted;
    ++version_;
    // This overload deliberately invalidates the affected suffix. The indexed
    // prefix remains valid; the next viewport lookup lazily rebuilds checkpoints.
    auto first = std::lower_bound(checkpoints_.begin(), checkpoints_.end(), startByte,
        [](const Checkpoint &checkpoint, uint64_t value) {
            return checkpoint.byte_offset < value;
        });
    checkpoints_.erase(first, checkpoints_.end());
    return true;
}

} // namespace windbell::largefile
