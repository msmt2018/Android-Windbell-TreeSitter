#include "PieceTreeSource.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <new>

namespace windbell::largefile {

struct PieceTreeSource::Node {
    Buffer buffer;
    uint64_t source_offset;
    uint64_t length;
    uint64_t subtree_bytes;
    uint64_t subtree_pieces;
    uint64_t priority;
    std::unique_ptr<Node> left;
    std::unique_ptr<Node> right;

    Node(Buffer b, uint64_t o, uint64_t n, uint64_t p) noexcept
        : buffer(b), source_offset(o), length(n), subtree_bytes(n),
          subtree_pieces(1), priority(p) {}
};

PieceTreeSource::PieceTreeSource(std::shared_ptr<const TextSource> original,
                                 uint64_t memoryLimitBytes) noexcept
    : original_(std::move(original)), memory_limit_bytes_(memoryLimitBytes) {
    if (!original_ || (original_->sizeBytes() & 1u) != 0) return;
    const uint64_t size = original_->sizeBytes();
    if (size != 0 && memory_limit_bytes_ >= sizeof(Node))
        root_ = makeNode(Buffer::Original, 0, size);
    valid_ = size == 0 || static_cast<bool>(root_);
}

PieceTreeSource::~PieceTreeSource() = default;

std::unique_ptr<PieceTreeSource> PieceTreeSource::clone() const noexcept {
    auto copy = std::unique_ptr<PieceTreeSource>(
        new (std::nothrow) PieceTreeSource(original_, memory_limit_bytes_));
    if (!copy || !copy->valid_) return nullptr;
    copy->root_ = cloneNode(root_.get());
    if (root_ && !copy->root_) return nullptr;
    try {
        copy->added_chunks_ = added_chunks_;
    } catch (...) {
        return nullptr;
    }
    copy->added_allocated_ = added_allocated_;
    copy->added_size_ = added_size_;
    constexpr uint64_t chunkBytes = 64u * 1024u;
    if ((copy->added_size_ % chunkBytes) != 0) {
        copy->added_size_ += chunkBytes - (copy->added_size_ % chunkBytes);
    }
    copy->version_ = version_;
    copy->random_state_ = random_state_;
    copy->valid_ = valid_;
    return copy;
}

bool PieceTreeSource::isValid() const noexcept { return valid_; }
uint64_t PieceTreeSource::sizeBytes() const noexcept { return subtreeBytes(root_); }
uint64_t PieceTreeSource::allocatedBytes() const noexcept {
    return added_allocated_ + countNodes(root_.get()) * sizeof(Node);
}
uint64_t PieceTreeSource::pieceCount() const noexcept { return countNodes(root_.get()); }
uint64_t PieceTreeSource::version() const noexcept { return version_; }

uint64_t PieceTreeSource::subtreeBytes(const std::unique_ptr<Node> &node) noexcept {
    return node ? node->subtree_bytes : 0;
}

void PieceTreeSource::updateNode(Node *node) noexcept {
    if (!node) return;
    node->subtree_bytes = node->length +
        (node->left ? node->left->subtree_bytes : 0) +
        (node->right ? node->right->subtree_bytes : 0);
    node->subtree_pieces = 1 +
        (node->left ? node->left->subtree_pieces : 0) +
        (node->right ? node->right->subtree_pieces : 0);
}

std::unique_ptr<PieceTreeSource::Node> PieceTreeSource::merge(
    std::unique_ptr<Node> left, std::unique_ptr<Node> right) noexcept {
    if (!left) return right;
    if (!right) return left;
    if (left->priority > right->priority) {
        left->right = merge(std::move(left->right), std::move(right));
        updateNode(left.get());
        return left;
    }
    right->left = merge(std::move(left), std::move(right->left));
    updateNode(right.get());
    return right;
}

std::unique_ptr<PieceTreeSource::Node> PieceTreeSource::cloneNode(const Node *node) noexcept {
    if (!node) return nullptr;
    auto copy = std::unique_ptr<Node>(new (std::nothrow) Node(
        node->buffer, node->source_offset, node->length, node->priority));
    if (!copy) return nullptr;
    copy->left = cloneNode(node->left.get());
    if (node->left && !copy->left) return nullptr;
    copy->right = cloneNode(node->right.get());
    if (node->right && !copy->right) return nullptr;
    updateNode(copy.get());
    return copy;
}

std::unique_ptr<PieceTreeSource::Node> PieceTreeSource::makeNode(
    Buffer buffer, uint64_t offset, uint64_t length) noexcept {
    if (length == 0) return nullptr;
    random_state_ ^= random_state_ >> 12;
    random_state_ ^= random_state_ << 25;
    random_state_ ^= random_state_ >> 27;
    const uint64_t priority = random_state_ * 0x2545F4914F6CDD1DULL;
    return std::unique_ptr<Node>(new (std::nothrow) Node(buffer, offset, length, priority));
}

bool PieceTreeSource::split(std::unique_ptr<Node> root, uint64_t offset,
                            std::unique_ptr<Node> &left,
                            std::unique_ptr<Node> &right) noexcept {
    if (!root) {
        if (offset != 0) return false;
        left.reset();
        right.reset();
        return true;
    }
    const uint64_t left_bytes = subtreeBytes(root->left);
    if (offset < left_bytes) {
        std::unique_ptr<Node> a, b;
        if (!split(std::move(root->left), offset, a, b)) return false;
        root->left = std::move(b);
        updateNode(root.get());
        left = std::move(a);
        right = std::move(root);
        return true;
    }
    if (offset > left_bytes + root->length) {
        std::unique_ptr<Node> a, b;
        if (!split(std::move(root->right), offset - left_bytes - root->length, a, b)) return false;
        root->right = std::move(a);
        updateNode(root.get());
        left = std::move(root);
        right = std::move(b);
        return true;
    }
    if (offset == left_bytes) {
        auto old_left = std::move(root->left);
        root->left.reset();
        updateNode(root.get());
        left = std::move(old_left);
        right = std::move(root);
        return true;
    }
    if (offset == left_bytes + root->length) {
        auto old_right = std::move(root->right);
        root->right.reset();
        updateNode(root.get());
        left = std::move(root);
        right = std::move(old_right);
        return true;
    }

    const uint64_t in_piece = offset - left_bytes;
    auto left_piece = makeNode(root->buffer, root->source_offset, in_piece);
    auto right_piece = makeNode(root->buffer, root->source_offset + in_piece,
                                root->length - in_piece);
    if (!left_piece || !right_piece) return false;
    auto old_left = std::move(root->left);
    auto old_right = std::move(root->right);
    left = merge(std::move(old_left), std::move(left_piece));
    right = merge(std::move(right_piece), std::move(old_right));
    return true;
}

uint64_t PieceTreeSource::countNodes(const Node *node) noexcept {
    return node ? node->subtree_pieces : 0;
}

bool PieceTreeSource::appendAdded(const uint8_t *data, uint64_t bytes,
                                  uint64_t *startOffset) noexcept {
    if (!startOffset || (bytes != 0 && !data) || (bytes & 1u) != 0) return false;
    constexpr uint64_t chunkBytes = 64u * 1024u;
    const uint64_t tailCapacity = added_allocated_ - added_size_;
    const uint64_t bytesBeyondTail = bytes > tailCapacity ? bytes - tailCapacity : 0;
    if (bytesBeyondTail > std::numeric_limits<uint64_t>::max() - (chunkBytes - 1u))
        return false;
    const uint64_t requiredChunks = bytesBeyondTail == 0 ? 0 :
        (bytesBeyondTail + chunkBytes - 1u) / chunkBytes;
    if (requiredChunks > (memory_limit_bytes_ -
        std::min(memory_limit_bytes_, added_allocated_)) / chunkBytes) return false;

    // Allocate every required chunk and reserve the destination vector before
    // changing logical size or writing into the current tail. A failed append
    // therefore cannot leave a partially advanced append arena behind.
    if (requiredChunks > static_cast<uint64_t>(std::numeric_limits<size_t>::max()) ||
        requiredChunks > static_cast<uint64_t>(std::numeric_limits<size_t>::max() -
                                               added_chunks_.size())) return false;
    const size_t requiredChunkCount = static_cast<size_t>(requiredChunks);
    std::vector<std::shared_ptr<uint8_t>> pending;
    try {
        pending.reserve(requiredChunkCount);
        added_chunks_.reserve(added_chunks_.size() + requiredChunkCount);
        for (uint64_t i = 0; i < requiredChunks; ++i) {
            std::shared_ptr<uint8_t> chunk(
                new (std::nothrow) uint8_t[chunkBytes], std::default_delete<uint8_t[]>());
            if (!chunk) return false;
            pending.push_back(std::move(chunk));
        }
    } catch (...) {
        return false;
    }

    *startOffset = added_size_;
    for (auto &chunk : pending) {
        added_chunks_.push_back(std::move(chunk));
        added_allocated_ += chunkBytes;
    }

    uint64_t consumed = 0;
    while (consumed < bytes) {
        const size_t index = static_cast<size_t>(added_size_ / chunkBytes);
        const size_t inChunk = static_cast<size_t>(added_size_ % chunkBytes);
        const uint64_t count = std::min<uint64_t>(bytes - consumed, chunkBytes - inChunk);
        std::memcpy(added_chunks_[index].get() + inChunk, data + consumed,
                    static_cast<size_t>(count));
        consumed += count;
        added_size_ += count;
    }
    return true;
}

bool PieceTreeSource::readAdded(uint64_t offset, uint32_t maxBytes,
                                TextChunkView *out) const noexcept {
    if (!out) return false;
    out->data = nullptr; out->size = 0;
    constexpr uint64_t chunkBytes = 64u * 1024u;
    if (offset > added_size_ || (offset & 1u) || maxBytes < 2u) return false;
    if (offset == added_size_) return true;
    const uint64_t index64 = offset / chunkBytes;
    if (index64 > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) return false;
    const size_t index = static_cast<size_t>(index64);
    const size_t local = static_cast<size_t>(offset % chunkBytes);
    if (index >= added_chunks_.size()) return false;
    const uint64_t count = std::min<uint64_t>(
        std::min<uint64_t>(added_size_ - offset, chunkBytes - local), maxBytes) & ~uint64_t{1};
    if (count == 0) return false;
    out->data = reinterpret_cast<const char *>(added_chunks_[index].get() + local);
    out->size = static_cast<uint32_t>(count);
    return true;
}

bool PieceTreeSource::readPiece(const Node *piece, uint64_t localOffset,
                                uint32_t maxBytes, TextChunkView *out) const noexcept {
    if (!piece || localOffset > piece->length) return false;
    const uint64_t available = piece->length - localOffset;
    const uint32_t request = static_cast<uint32_t>(
        std::min<uint64_t>(available, maxBytes) & ~uint64_t{1});
    if (request == 0) return false;
    if (piece->buffer == Buffer::Original) {
        return original_->read(piece->source_offset + localOffset, request, out);
    }
    return readAdded(piece->source_offset + localOffset, request, out);
}

bool PieceTreeSource::read(uint64_t offset, uint32_t maxBytes,
                           TextChunkView *out) const noexcept {
    if (!out) return false;
    out->data = nullptr; out->size = 0;
    const uint64_t total = sizeBytes();
    if (!valid_ || (offset & 1u) || offset > total || maxBytes < 2u) return false;
    if (offset == total) return true;
    const Node *node = root_.get();
    uint64_t remaining = offset;
    while (node) {
        const uint64_t leftBytes = node->left ? node->left->subtree_bytes : 0;
        if (remaining < leftBytes) { node = node->left.get(); continue; }
        if (remaining < leftBytes + node->length) {
            return readPiece(node, remaining - leftBytes, maxBytes, out);
        }
        remaining -= leftBytes + node->length;
        node = node->right.get();
    }
    return false;
}

bool PieceTreeSource::replace(uint64_t startByte, uint64_t oldEndByte,
                              const void *inserted, uint64_t insertedBytes) noexcept {
    if (!valid_ || (startByte & 1u) || (oldEndByte & 1u) ||
        (insertedBytes & 1u) || oldEndByte < startByte ||
        oldEndByte > sizeBytes() || (insertedBytes != 0 && !inserted)) return false;
    constexpr uint64_t chunkBytes = 64u * 1024u;
    const uint64_t tailCapacity = added_allocated_ - added_size_;
    const uint64_t bytesBeyondTail = insertedBytes > tailCapacity
        ? insertedBytes - tailCapacity : 0;
    if (bytesBeyondTail > std::numeric_limits<uint64_t>::max() - (chunkBytes - 1u))
        return false;
    const uint64_t requiredChunks = bytesBeyondTail == 0 ? 0 :
        (bytesBeyondTail + chunkBytes - 1u) / chunkBytes;
    if (requiredChunks > std::numeric_limits<uint64_t>::max() / chunkBytes)
        return false;
    const uint64_t additionalChunks = requiredChunks * chunkBytes;
    if (additionalChunks > memory_limit_bytes_ -
        std::min(memory_limit_bytes_, added_allocated_) ||
        additionalChunks > std::numeric_limits<uint64_t>::max() - added_allocated_)
        return false;
    const uint64_t projectedAdded = added_allocated_ + additionalChunks;
    const uint64_t maximumPieces = (memory_limit_bytes_ - projectedAdded) / sizeof(Node);
    const uint64_t splitNodes = insertedBytes ? 3u : 2u;
    if (countNodes(root_.get()) + splitNodes > maximumPieces) return false;

    // Reserve the insertion node and split the tree before appending bytes.
    // If allocation or range splitting fails, no append-arena bytes are consumed.
    const uint64_t addOffset = added_size_;
    auto insertedPiece = insertedBytes
        ? makeNode(Buffer::Added, addOffset, insertedBytes) : nullptr;
    if (insertedBytes && !insertedPiece) return false;

    std::unique_ptr<Node> prefix, tail, removed, suffix;
    if (!split(std::move(root_), oldEndByte, tail, suffix) ||
        !split(std::move(tail), startByte, prefix, removed)) {
        root_ = merge(std::move(prefix), merge(std::move(removed), std::move(suffix)));
        return false;
    }
    if (insertedBytes && !appendAdded(static_cast<const uint8_t *>(inserted),
                                      insertedBytes, &addOffset)) {
        root_ = merge(std::move(prefix), merge(std::move(removed), std::move(suffix)));
        return false;
    }
    if (insertedPiece) insertedPiece->source_offset = addOffset;
    removed.reset();
    root_ = merge(merge(std::move(prefix), std::move(insertedPiece)), std::move(suffix));
    ++version_;
    return true;
}

} // namespace windbell::largefile
