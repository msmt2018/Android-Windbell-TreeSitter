#pragma once

#include "TextSource.h"

#include <cstdint>
#include <vector>

namespace windbell::largefile {

/**
 * Sparse native line/partition index for UTF-16LE documents.
 *
 * A checkpoint is recorded every strideLines logical lines. Queries start at
 * the nearest preceding checkpoint and scan only the remaining local range.
 * After edits, unaffected suffix checkpoints are shifted by byte/line deltas;
 * checkpoints intersecting the replaced range are discarded.
 *
 * The owner must serialize index access with document edits.
 */
class TextPartitionIndex final {
public:
    struct Checkpoint {
        uint64_t byte_offset = 0;
        uint64_t line = 0;
    };

    explicit TextPartitionIndex(uint32_t strideLines = 256) noexcept;

    void reset() noexcept;
    bool lineForByte(const TextSource &source, uint64_t byteOffset,
                     uint64_t *line) noexcept;
    bool byteForLine(const TextSource &source, uint64_t line,
                     uint64_t *byteOffset) noexcept;

    // Prepare checkpoint deltas against the old source before source.replace().
    bool applyEdit(const TextSource &oldSource,
                   uint64_t startByte, uint64_t oldEndByte,
                   const void *inserted, uint64_t insertedBytes) noexcept;

    const std::vector<Checkpoint> &checkpoints() const noexcept { return checkpoints_; }
    uint64_t version() const noexcept { return version_; }

private:
    bool scan(const TextSource &source, uint64_t startByte, uint64_t startLine,
              uint64_t targetByte, uint64_t targetLine,
              bool stopByLine, uint64_t *result,
              uint64_t *resultLine = nullptr) noexcept;
    bool countNewlines(const TextSource &source, uint64_t startByte,
                       uint64_t endByte, uint64_t *count) noexcept;
    void addCheckpoint(uint64_t byteOffset, uint64_t line) noexcept;

    uint32_t stride_lines_;
    uint64_t version_ = 0;
    std::vector<Checkpoint> checkpoints_;
};

} // namespace windbell::largefile
