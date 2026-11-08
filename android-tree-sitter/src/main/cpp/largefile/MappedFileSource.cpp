#include "MappedFileSource.h"

#include <limits>
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

bool MappedFileSource::read(
    uint64_t offset,
    uint32_t maxBytes,
    TextChunkView *out
) const noexcept {
    if (out == nullptr) return false;
    out->data = nullptr;
    out->size = 0;

    if (!open_) return false;
    if (offset == size_bytes_) return true;
    if (mapping_ == nullptr || offset > size_bytes_ || maxBytes == 0) return false;
    if (offset > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) return false;

    const uint64_t count = (size_bytes_ - offset) < maxBytes
        ? (size_bytes_ - offset) : maxBytes;
    out->data = static_cast<const char *>(mapping_) + static_cast<size_t>(offset);
    out->size = static_cast<uint32_t>(count);
    return true;
}

} // namespace windbell::largefile
