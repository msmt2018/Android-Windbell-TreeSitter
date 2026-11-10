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
    try {
        checkpoints_.push_back({0, 0});
    } catch (...) {
        // Empty means unavailable; queries fail safely until a later reset.
    }
    ++version_;
}

void TextPartitionIndex::addCheckpoint(uint64_t byteOffset, uint64_t line) noexcept {
    // Queries are not guaranteed to arrive in document order: editors can ask
    // for a distant line and then jump back to the beginning. Keep the vector
    // sorted because every lookup uses upper_bound().
    auto position = std::lower_bound(
        checkpoints_.begin(), checkpoints_.end(), byteOffset,
        [](const Checkpoint &checkpoint, uint64_t value) {
            return checkpoint.byte_offset < value;
        });
    if (position != checkpoints_.end() && position->byte_offset == byteOffset) {
        position->line = line;
        return;
    }
    try {
        if (position == checkpoints_.end()) checkpoints_.push_back({byteOffset, line});
        else checkpoints_.insert(position, {byteOffset, line});
    } catch (...) {
        // The index is an optimization. A failed checkpoint allocation leaves
        // the preceding valid sparse index usable.
    }
}

bool TextPartitionIndex::scan(const TextSource &source, uint64_t startByte,
                              uint64_t startLine, uint64_t targetByte,
                              uint64_t targetLine, bool stopByLine,
                              uint64_t *result, uint64_t *resultLine,
                              uint64_t *resultLineStart) noexcept {
    if (!result || (startByte & 1u) || startByte > source.sizeBytes()) return false;
    uint64_t offset = startByte;
    uint64_t line = startLine;
    uint64_t lineStart = startByte;
    if (!stopByLine && targetByte < startByte) return false;
    while (offset < source.sizeBytes()) {
        if ((!stopByLine && offset >= targetByte) ||
            (stopByLine && line >= targetLine)) {
            *result = offset;
            if (resultLine) *resultLine = line;
            if (resultLineStart) *resultLineStart = lineStart;
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
                lineStart = offset;
                if (line % stride_lines_ == 0) addCheckpoint(offset, line);
                if ((!stopByLine && offset >= targetByte) ||
                    (stopByLine && line >= targetLine)) {
                    *result = offset;
                    if (resultLine) *resultLine = line;
                    if (resultLineStart) *resultLineStart = lineStart;
                    return true;
                }
            }
            if (!stopByLine && offset >= targetByte) {
                *result = offset;
                if (resultLine) *resultLine = line;
                if (resultLineStart) *resultLineStart = lineStart;
                return true;
            }
        }
    }
    *result = offset;
    if (resultLine) *resultLine = line;
    if (resultLineStart) *resultLineStart = lineStart;
    return !stopByLine || line >= targetLine;
}

bool TextPartitionIndex::lineForByte(const TextSource &source,
                                     uint64_t byteOffset,
                                     uint64_t *line) noexcept {
    if (!line || checkpoints_.empty() || (byteOffset & 1u) || byteOffset > source.sizeBytes()) return false;
    auto it = std::upper_bound(checkpoints_.begin(), checkpoints_.end(), byteOffset,
        [](uint64_t value, const Checkpoint &checkpoint) {
            return value < checkpoint.byte_offset;
        });
    if (it != checkpoints_.begin()) --it;
    // scan() may append checkpoints and reallocate the vector, so never keep
    // an iterator alive across that call.
    const uint64_t checkpointByte = it->byte_offset;
    const uint64_t checkpointLine = it->line;
    uint64_t ignored = 0;
    uint64_t resolvedLine = checkpointLine;
    if (!scan(source, checkpointByte, checkpointLine, byteOffset, 0, false,
              &ignored, &resolvedLine)) return false;
    // scan() already counted line feeds while traversing the requested range.
    // Do not reread the same source window just to recover the resulting row.
    *line = resolvedLine;
    return true;
}

bool TextPartitionIndex::pointForByte(const TextSource &source,
                                      uint64_t byteOffset,
                                      uint64_t *line,
                                      uint64_t *lineStartByte) noexcept {
    if (!line || !lineStartByte || checkpoints_.empty() ||
        (byteOffset & 1u) || byteOffset > source.sizeBytes()) return false;
    auto it = std::upper_bound(checkpoints_.begin(), checkpoints_.end(), byteOffset,
        [](uint64_t value, const Checkpoint &checkpoint) {
            return value < checkpoint.byte_offset;
        });
    if (it != checkpoints_.begin()) --it;
    // Copy checkpoint values before scan(), which may append and reallocate.
    const uint64_t checkpointByte = it->byte_offset;
    const uint64_t checkpointLine = it->line;
    uint64_t ignored = 0;
    uint64_t resolvedLine = checkpointLine;
    uint64_t resolvedLineStart = checkpointByte;
    if (!scan(source, checkpointByte, checkpointLine, byteOffset, 0, false,
              &ignored, &resolvedLine, &resolvedLineStart)) return false;
    *line = resolvedLine;
    *lineStartByte = resolvedLineStart;
    return true;
}

bool TextPartitionIndex::byteForLine(const TextSource &source,
                                     uint64_t requestedLine,
                                     uint64_t *byteOffset) noexcept {
    if (!byteOffset || checkpoints_.empty()) return false;
    auto it = std::upper_bound(checkpoints_.begin(), checkpoints_.end(), requestedLine,
        [](uint64_t value, const Checkpoint &checkpoint) {
            return value < checkpoint.line;
        });
    if (it != checkpoints_.begin()) --it;
    return scan(source, it->byte_offset, it->line, 0, requestedLine, true, byteOffset, nullptr);
}

bool TextPartitionIndex::applyEdit(const TextSource &oldSource,
                                   uint64_t startByte, uint64_t oldEndByte,
                                   const void *inserted, uint64_t insertedBytes) noexcept {
    if ((startByte & 1u) || (oldEndByte & 1u) || (insertedBytes & 1u) ||
        oldEndByte < startByte || oldEndByte > oldSource.sizeBytes() ||
        startByte > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) || oldEndByte > static_cast<uint64_t>(INT64_MAX) ||
        insertedBytes > static_cast<uint64_t>(INT64_MAX) ||
        (insertedBytes && !inserted)) return false;

    uint64_t startLine = 0;
    uint64_t ignoredLineStart = 0;
    uint64_t endLine = 0;
    uint64_t ignoredOffset = 0;
    if (!pointForByte(oldSource, startByte, &startLine, &ignoredLineStart) ||
        !scan(oldSource, startByte, startLine, oldEndByte, 0, false,
              &ignoredOffset, &endLine)) return false;
    // Derive removed newline count from the same bounded scan used to resolve
    // the old end point; do not reread the replaced text a second time.
    const uint64_t removedLines = endLine - startLine;

    uint64_t insertedLines = 0;
    const auto *bytes = static_cast<const uint8_t *>(inserted);
    for (uint64_t i = 0; i + 1 < insertedBytes; i += 2) {
        const uint16_t unit = static_cast<uint16_t>(bytes[i]) |
            (static_cast<uint16_t>(bytes[i + 1]) << 8u);
        if (unit == '\n') ++insertedLines;
    }

    const int64_t byteDelta = static_cast<int64_t>(insertedBytes) -
        static_cast<int64_t>(oldEndByte - startByte);
    const int64_t lineDelta = static_cast<int64_t>(insertedLines) -
        static_cast<int64_t>(removedLines);
    std::vector<Checkpoint> updated;
    try {
        updated.reserve(checkpoints_.size() +
            static_cast<size_t>(insertedLines / stride_lines_) + 2u);
        for (const auto &checkpoint : checkpoints_) {
            if (checkpoint.byte_offset <= startByte) updated.push_back(checkpoint);
        }
        uint64_t line = startLine;
        for (uint64_t i = 0; i + 1 < insertedBytes; i += 2) {
            const uint16_t unit = static_cast<uint16_t>(bytes[i]) |
                (static_cast<uint16_t>(bytes[i + 1]) << 8u);
            if (unit == '\n') {
                ++line;
                if (line % stride_lines_ == 0) {
                    const uint64_t offset = startByte + i + 2u;
                    if (updated.empty() || updated.back().byte_offset != offset)
                        updated.push_back({offset, line});
                }
            }
        }
        for (const auto &checkpoint : checkpoints_) {
            if (checkpoint.byte_offset < oldEndByte) continue;
            const auto shiftOffset = [](uint64_t value, int64_t delta,
                                        uint64_t *shifted) noexcept {
                const uint64_t maximum = static_cast<uint64_t>(
                    std::numeric_limits<int64_t>::max());
                if (!shifted || value > maximum) return false;
                if (delta >= 0) {
                    const uint64_t amount = static_cast<uint64_t>(delta);
                    if (value > maximum - amount) return false;
                    *shifted = value + amount;
                    return true;
                }
                const uint64_t amount = static_cast<uint64_t>(-(delta + 1)) + 1u;
                if (value < amount) return false;
                *shifted = value - amount;
                return true;
            };
            Checkpoint shifted{};
            if (!shiftOffset(checkpoint.byte_offset, byteDelta, &shifted.byte_offset) ||
                !shiftOffset(checkpoint.line, lineDelta, &shifted.line)) continue;
            if (updated.empty() || updated.back().byte_offset < shifted.byte_offset)
                updated.push_back(shifted);
        }
    } catch (...) {
        return false;
    }
    checkpoints_.swap(updated);
    ++version_;
    return true;
}

} // namespace windbell::largefile
