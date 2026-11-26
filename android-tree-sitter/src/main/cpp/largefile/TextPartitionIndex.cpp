#include "TextPartitionIndex.h"

#include <algorithm>
#include <cstring>
#include <limits>

namespace windbell::largefile {

namespace {

constexpr size_t kCheckpointMemoryBudget = 2u * 1024u * 1024u;
constexpr size_t kMaxCheckpointCount =
    kCheckpointMemoryBudget / sizeof(TextPartitionIndex::Checkpoint);

void compactCheckpointsInPlace(
    std::vector<TextPartitionIndex::Checkpoint> &checkpoints
) noexcept {
    if (checkpoints.size() < kMaxCheckpointCount || checkpoints.empty()) return;

    const auto last = checkpoints.back();
    size_t write = 0;
    for (size_t read = 0; read < checkpoints.size(); read += 2u) {
        checkpoints[write++] = checkpoints[read];
    }
    if (write == 0u || checkpoints[write - 1u].byte_offset != last.byte_offset) {
        checkpoints[write++] = last;
    }
    checkpoints.resize(write);
}

bool ensureCheckpointCapacity(
    std::vector<TextPartitionIndex::Checkpoint> &checkpoints,
    size_t required
) noexcept {
    if (required > kMaxCheckpointCount) return false;
    if (checkpoints.capacity() >= required) {
        return checkpoints.capacity() <= kMaxCheckpointCount;
    }

    size_t capacity = checkpoints.capacity() == 0u ? 8u : checkpoints.capacity();
    while (capacity < required && capacity < kMaxCheckpointCount) {
        capacity = std::min(kMaxCheckpointCount, capacity * 2u);
    }
    try {
        checkpoints.reserve(capacity);
    } catch (...) {
        return false;
    }
    return checkpoints.capacity() >= required &&
        checkpoints.capacity() <= kMaxCheckpointCount;
}

bool appendBoundedCheckpoint(
    std::vector<TextPartitionIndex::Checkpoint> &checkpoints,
    const TextPartitionIndex::Checkpoint &checkpoint
) noexcept {
    if (!checkpoints.empty()) {
        if (checkpoint.byte_offset < checkpoints.back().byte_offset) return false;
        if (checkpoint.byte_offset == checkpoints.back().byte_offset) {
            checkpoints.back().line = checkpoint.line;
            return true;
        }
    }
    if (checkpoints.size() >= kMaxCheckpointCount) {
        compactCheckpointsInPlace(checkpoints);
    }
    if (!ensureCheckpointCapacity(checkpoints, checkpoints.size() + 1u)) return false;
    try {
        checkpoints.push_back(checkpoint);
        return true;
    } catch (...) {
        return false;
    }
}

} // namespace


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
    // Queries can arrive in arbitrary document order. Keep the sparse index
    // sorted, but cap retained checkpoint storage so distant viewport jumps
    // cannot grow this optimization without bound.
    auto position = std::lower_bound(
        checkpoints_.begin(), checkpoints_.end(), byteOffset,
        [](const Checkpoint &checkpoint, uint64_t value) {
            return checkpoint.byte_offset < value;
        });
    if (position != checkpoints_.end() && position->byte_offset == byteOffset) {
        position->line = line;
        return;
    }

    if (checkpoints_.size() >= kMaxCheckpointCount) {
        compactCheckpointsInPlace(checkpoints_);
    }
    if (!ensureCheckpointCapacity(checkpoints_, checkpoints_.size() + 1u)) return;

    position = std::lower_bound(
        checkpoints_.begin(), checkpoints_.end(), byteOffset,
        [](const Checkpoint &checkpoint, uint64_t value) {
            return checkpoint.byte_offset < value;
        });
    try {
        checkpoints_.insert(position, {byteOffset, line});
    } catch (...) {
        // The index is optional. Keep the preceding valid sparse index on
        // allocation pressure; callers can still scan from an older checkpoint.
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
    const uint64_t maximum = static_cast<uint64_t>(
        std::numeric_limits<int64_t>::max());
    if ((startByte & 1u) || (oldEndByte & 1u) || (insertedBytes & 1u) ||
        oldEndByte < startByte || oldEndByte > oldSource.sizeBytes() ||
        startByte > maximum || oldEndByte > maximum ||
        insertedBytes > maximum || (insertedBytes && !inserted)) return false;

    uint64_t startLine = 0;
    uint64_t oldStartLineStart = 0;
    uint64_t endLine = 0;
    uint64_t oldEndLineStart = 0;
    uint64_t ignoredOffset = 0;
    if (!pointForByte(oldSource, startByte, &startLine, &oldStartLineStart) ||
        !scan(oldSource, startByte, startLine, oldEndByte, 0, false,
              &ignoredOffset, &endLine, &oldEndLineStart)) return false;
    const uint64_t removedLines = endLine - startLine;

    uint64_t insertedLines = 0;
    const auto *bytes = static_cast<const uint8_t *>(inserted);
    for (uint64_t i = 0; i + 1u < insertedBytes; i += 2u) {
        const uint16_t unit = static_cast<uint16_t>(bytes[i]) |
            (static_cast<uint16_t>(bytes[i + 1u]) << 8u);
        if (unit == '\n') ++insertedLines;
    }

    const uint16_t finalInsertedUnit = insertedBytes >= 2u
        ? static_cast<uint16_t>(bytes[insertedBytes - 2u]) |
            (static_cast<uint16_t>(bytes[insertedBytes - 1u]) << 8u)
        : 0u;
    const bool insertedEndsWithLineFeed =
        insertedBytes >= 2u && finalInsertedUnit == '\n';
    // The old suffix checkpoint at oldEndByte remains a line start only if
    // the replacement ends in LF, or a deletion joins two old line starts.
    const bool suffixStartsAtLineBoundary = insertedBytes == 0u
        ? startByte == oldStartLineStart && oldEndByte == oldEndLineStart
        : insertedEndsWithLineFeed;

    const int64_t byteDelta = static_cast<int64_t>(insertedBytes) -
        static_cast<int64_t>(oldEndByte - startByte);
    const int64_t lineDelta = static_cast<int64_t>(insertedLines) -
        static_cast<int64_t>(removedLines);

    std::vector<Checkpoint> updated;
    try {
        const uint64_t estimated = static_cast<uint64_t>(checkpoints_.size()) +
            insertedLines / stride_lines_ + 2u;
        const size_t initialCapacity = static_cast<size_t>(
            std::min<uint64_t>(estimated, kMaxCheckpointCount));
        if (initialCapacity > 0u &&
            !ensureCheckpointCapacity(updated, initialCapacity)) return false;

        for (const auto &checkpoint : checkpoints_) {
            if (checkpoint.byte_offset <= startByte &&
                !appendBoundedCheckpoint(updated, checkpoint)) return false;
        }

        uint64_t line = startLine;
        for (uint64_t i = 0; i + 1u < insertedBytes; i += 2u) {
            const uint16_t unit = static_cast<uint16_t>(bytes[i]) |
                (static_cast<uint16_t>(bytes[i + 1u]) << 8u);
            if (unit == '\n') {
                ++line;
                if (line % stride_lines_ == 0u) {
                    const uint64_t relativeOffset = i + 2u;
                    if (relativeOffset > maximum - startByte) return false;
                    const uint64_t offset = startByte + relativeOffset;
                    if (!appendBoundedCheckpoint(updated, {offset, line})) return false;
                }
            }
        }

        const auto shiftOffset = [maximum](uint64_t value, int64_t delta,
                                           uint64_t *shifted) noexcept {
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

        for (const auto &checkpoint : checkpoints_) {
            if (checkpoint.byte_offset < oldEndByte) continue;
            if (checkpoint.byte_offset == oldEndByte &&
                !suffixStartsAtLineBoundary) continue;

            Checkpoint shifted{};
            if (!shiftOffset(checkpoint.byte_offset, byteDelta, &shifted.byte_offset) ||
                !shiftOffset(checkpoint.line, lineDelta, &shifted.line)) return false;
            if (!appendBoundedCheckpoint(updated, shifted)) return false;
        }
    } catch (...) {
        return false;
    }

    checkpoints_.swap(updated);
    ++version_;
    return true;
}

} // namespace windbell::largefile
