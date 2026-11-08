/*
 * Read-only memory-mapped file source.
 *
 * Mapping is demand-paged by the operating system: opening a large file does
 * not eagerly copy its contents into a native heap buffer. The mapping remains
 * stable for the lifetime of this object and is safe for concurrent readers.
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

private:
    void *mapping_ = nullptr;
    uint64_t size_bytes_ = 0;
};

} // namespace windbell::largefile
