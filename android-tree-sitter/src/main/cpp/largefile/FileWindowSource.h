/*
 * Fixed-memory, windowed UTF-16LE file source.
 *
 * Unlike mmap, this source reserves only one bounded read window and uses
 * pread(), so it does not need a large contiguous virtual address range and
 * cannot SIGBUS when another process truncates the backing file. A source
 * instance is single-reader: the returned view is invalidated by the next read.
 */
#pragma once

#include "TextSource.h"

#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace windbell::largefile {

class FileWindowSource final : public TextSource {
public:
    explicit FileWindowSource(uint32_t windowBytes = kTreeSitterInputChunkBytes) noexcept;
    explicit FileWindowSource(const std::string &path,
                              uint32_t windowBytes = kTreeSitterInputChunkBytes) noexcept;
    ~FileWindowSource() override;

    FileWindowSource(const FileWindowSource &) = delete;
    FileWindowSource &operator=(const FileWindowSource &) = delete;

    bool open(const std::string &path) noexcept;
    void close() noexcept;
    bool isOpen() const noexcept;

    uint64_t sizeBytes() const noexcept override;
    bool read(uint64_t offset, uint32_t maxBytes, TextChunkView *out) const noexcept override;

private:
    int fd_ = -1;
    uint64_t size_bytes_ = 0;
    uint32_t window_bytes_;
    mutable std::vector<char> window_;
    mutable uint64_t window_offset_ = 0;
    mutable uint32_t window_size_ = 0;
};

} // namespace windbell::largefile
