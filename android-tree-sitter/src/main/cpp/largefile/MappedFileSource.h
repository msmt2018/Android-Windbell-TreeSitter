/*
 * Read-only memory-mapped file source.
 *
 * Mapping is demand-paged by the operating system: opening a large file does
 * not eagerly copy its contents into a native heap buffer. The source is a
 * read-only UTF-16LE snapshot view. The backing file must not be truncated or
 * rewritten while mapped; callers should map immutable snapshots, not live edit files.
 */
#pragma once

#include "TextSource.h"

#include <cstdint>
#include <string>

namespace windbell::largefile {

class MappedFileSource final : public TextSource {
public:
    MappedFileSource() noexcept = default;
    explicit MappedFileSource(const std::string &path) noexcept;
    ~MappedFileSource() override;

    MappedFileSource(const MappedFileSource &) = delete;
    MappedFileSource &operator=(const MappedFileSource &) = delete;
    MappedFileSource(MappedFileSource &&other) noexcept;
    MappedFileSource &operator=(MappedFileSource &&other) noexcept;

    bool open(const std::string &path) noexcept;
    void close() noexcept;
    bool isOpen() const noexcept;

    uint64_t sizeBytes() const noexcept override;
    bool read(uint64_t offset, uint32_t maxBytes, TextChunkView *out) const noexcept override;
    bool contiguousData(const char **data, uint64_t *sizeBytes) const noexcept override;

private:
    void *mapping_ = nullptr;
    uint64_t size_bytes_ = 0;
    bool open_ = false;
};

} // namespace windbell::largefile
