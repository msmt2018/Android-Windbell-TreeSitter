#include "FileWindowSource.h"

#include <algorithm>
#include <cerrno>
#include <limits>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

namespace windbell::largefile {

FileWindowSource::FileWindowSource(uint32_t windowBytes) noexcept
    : window_bytes_(std::max<uint32_t>(2u, windowBytes & ~1u)) {
    try {
        window_.resize(window_bytes_);
    } catch (...) {
        window_bytes_ = 0;
    }
}

FileWindowSource::FileWindowSource(
    const std::string &path,
    uint32_t windowBytes
) noexcept : FileWindowSource(windowBytes) {
    open(path);
}

FileWindowSource::~FileWindowSource() {
    close();
}

bool FileWindowSource::open(const std::string &path) noexcept {
    close();
    if (window_bytes_ < 2u || window_.size() != window_bytes_) return false;

    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    struct stat info {};
    if (::fstat(fd, &info) != 0 || info.st_size < 0) {
        ::close(fd);
        return false;
    }

    const uint64_t size = static_cast<uint64_t>(info.st_size);
    if ((size & 1u) != 0) {
        ::close(fd);
        return false;
    }

    fd_ = fd;
    size_bytes_ = size;
    window_offset_ = 0;
    window_size_ = 0;
    return true;
}

void FileWindowSource::close() noexcept {
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
    size_bytes_ = 0;
    window_offset_ = 0;
    window_size_ = 0;
}

bool FileWindowSource::isOpen() const noexcept {
    return fd_ >= 0;
}

uint64_t FileWindowSource::sizeBytes() const noexcept {
    return size_bytes_;
}

bool FileWindowSource::read(
    uint64_t offset,
    uint32_t maxBytes,
    TextChunkView *out
) const noexcept {
    if (out == nullptr) return false;
    out->data = nullptr;
    out->size = 0;
    if (fd_ < 0 || (offset & 1u) != 0) return false;
    if (offset == size_bytes_) return true;
    if (offset > size_bytes_ || maxBytes < 2u || window_bytes_ < 2u) return false;

    if (offset < window_offset_ || offset >= window_offset_ + window_size_) {
        window_offset_ = offset;
        const uint64_t available = size_bytes_ - offset;
        const uint32_t requested = static_cast<uint32_t>(
            std::min<uint64_t>(available, window_bytes_));
        uint32_t received = 0;
        while (received < requested) {
            const ssize_t n = ::pread(
                fd_, window_.data() + received, requested - received,
                static_cast<off_t>(offset + received));
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) {
                window_size_ = 0;
                return false;
            }
            received += static_cast<uint32_t>(n);
        }
        window_size_ = received & ~1u;
    }

    const uint64_t local = offset - window_offset_;
    if (local >= window_size_) return false;
    const uint64_t count = std::min<uint64_t>(
        std::min<uint64_t>(window_size_ - local, maxBytes), window_bytes_);
    const uint64_t aligned = count & ~uint64_t{1};
    if (aligned == 0) return false;

    out->data = window_.data() + static_cast<size_t>(local);
    out->size = static_cast<uint32_t>(aligned);
    return true;
}

} // namespace windbell::largefile
