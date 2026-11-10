#include "MappedFileSource.h"

#include <algorithm>
#include <limits>
#include <new>
#include <cerrno>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <utility>

namespace windbell::largefile {

MappedFileSource::MappedFileSource(const std::string &path) noexcept {
    open(path);
}

MappedFileSource::~MappedFileSource() {
    close();
}

MappedFileSource::MappedFileSource(MappedFileSource &&other) noexcept
    : mapping_(other.mapping_), size_bytes_(other.size_bytes_), open_(other.open_) {
    other.mapping_ = nullptr;
    other.size_bytes_ = 0;
    other.open_ = false;
}

MappedFileSource &MappedFileSource::operator=(MappedFileSource &&other) noexcept {
    if (this == &other) return *this;
    close();
    mapping_ = other.mapping_;
    size_bytes_ = other.size_bytes_;
    open_ = other.open_;
    other.mapping_ = nullptr;
    other.size_bytes_ = 0;
    other.open_ = false;
    return *this;
}

std::unique_ptr<MappedFileSource> MappedFileSource::createSnapshot(
    const TextSource &source, const std::string &directory
) noexcept {
    const uint64_t source_size = source.sizeBytes();
    if ((source_size & 1u) != 0 ||
        source_size > static_cast<uint64_t>(std::numeric_limits<off_t>::max()) ||
        directory.empty()) return nullptr;

    std::string temporary_path;
    int fd = -1;
    try {
        temporary_path = directory;
        if (temporary_path.back() != '/') temporary_path.push_back('/');
        temporary_path += "windbell-text-snapshot-XXXXXX";
        fd = ::mkstemp(temporary_path.data());
    } catch (...) {
        return nullptr;
    }
    if (fd < 0) return nullptr;

    bool success = ::ftruncate(fd, static_cast<off_t>(source_size)) == 0;
    uint64_t offset = 0;
    while (success && offset < source_size) {
        TextChunkView chunk{};
        const uint32_t request = static_cast<uint32_t>(
            std::min<uint64_t>(kTreeSitterInputChunkBytes, source_size - offset));
        if (!source.read(offset, request, &chunk) || !chunk.data ||
            chunk.size == 0 || (chunk.size & 1u) != 0 ||
            chunk.size > source_size - offset) {
            success = false;
            break;
        }

        uint32_t written = 0;
        while (written < chunk.size) {
            const ssize_t count = ::pwrite(
                fd, chunk.data + written,
                static_cast<size_t>(chunk.size - written),
                static_cast<off_t>(offset + written));
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) {
                success = false;
                break;
            }
            written += static_cast<uint32_t>(count);
        }
        if (success) offset += chunk.size;
    }

    if (::close(fd) != 0) success = false;
    fd = -1;
    if (!success || offset != source_size) {
        ::unlink(temporary_path.c_str());
        return nullptr;
    }

    std::unique_ptr<MappedFileSource> snapshot(
        new (std::nothrow) MappedFileSource());
    if (!snapshot || !snapshot->open(temporary_path)) {
        ::unlink(temporary_path.c_str());
        return nullptr;
    }
    // POSIX keeps the mapping alive after unlink. No stale snapshot file is
    // left behind, and the mapping remains immutable for its owner's lifetime.
    if (::unlink(temporary_path.c_str()) != 0) {
        snapshot->close();
        return nullptr;
    }
    return snapshot;
}

bool MappedFileSource::open(const std::string &path) noexcept {
    close();
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    struct stat info {};
    const bool stat_ok = ::fstat(fd, &info) == 0 && info.st_size >= 0;
    if (!stat_ok) {
        ::close(fd);
        return false;
    }

    if (info.st_size == 0) {
        ::close(fd);
        open_ = true;
        return true;
    }

    const auto size = static_cast<uint64_t>(info.st_size);
    if ((size & 1u) != 0) {
        ::close(fd);
        return false;
    }
    if (size > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
        ::close(fd);
        return false;
    }

    void *mapped = ::mmap(nullptr, static_cast<size_t>(size), PROT_READ, MAP_PRIVATE, fd, 0);
    ::close(fd);
    if (mapped == MAP_FAILED) return false;

    mapping_ = mapped;
    size_bytes_ = size;
    open_ = true;
    return true;
}

void MappedFileSource::close() noexcept {
    if (mapping_ != nullptr) {
        ::munmap(mapping_, static_cast<size_t>(size_bytes_));
        mapping_ = nullptr;
    }
    size_bytes_ = 0;
    open_ = false;
}

bool MappedFileSource::isOpen() const noexcept {
    return open_;
}

uint64_t MappedFileSource::sizeBytes() const noexcept {
    return size_bytes_;
}

bool MappedFileSource::contiguousData(
    const char **data, uint64_t *sizeBytes
) const noexcept {
    if (!data || !sizeBytes || !open_) return false;
    *data = static_cast<const char *>(mapping_);
    *sizeBytes = size_bytes_;
    return size_bytes_ == 0 || mapping_ != nullptr;
}

bool MappedFileSource::read(
    uint64_t offset,
    uint32_t maxBytes,
    TextChunkView *out
) const noexcept {
    if (out == nullptr) return false;
    out->data = nullptr;
    out->size = 0;

    if (!open_) return false;
    if ((offset & 1u) != 0) return false;
    if (offset == size_bytes_) return true;
    if (mapping_ == nullptr || offset > size_bytes_ || maxBytes < 2u) return false;
    if (offset > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) return false;

    uint64_t count = (size_bytes_ - offset) < maxBytes
        ? (size_bytes_ - offset) : maxBytes;
    count &= ~uint64_t{1};
    if (count == 0) return false;
    out->data = static_cast<const char *>(mapping_) + static_cast<size_t>(offset);
    out->size = static_cast<uint32_t>(count);
    return true;
}

} // namespace windbell::largefile
