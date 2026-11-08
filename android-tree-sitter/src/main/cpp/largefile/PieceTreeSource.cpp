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
    uint64_t priority;
    std::unique_ptr<Node> left;
    std::unique_ptr<Node> right;

    Node(Buffer b, uint64_t o, uint64_t n, uint64_t p) noexcept
        : buffer(b), source_offset(o), length(n), subtree_bytes(n), priority(p) {}
};

PieceTreeSource::PieceTreeSource(std::shared_ptr<const TextSource> original,
                                 uint64_t memoryLimitBytes) noexcept
    : original_(std::move(original)), memory_limit_bytes_(memoryLimitBytes) {
    if (!original_ || (original_->sizeBytes() & 1u) != 0) return;
    const uint64_t size = original_->sizeBytes();
    if (size != 0) root_ = makeNode(Buffer::Original, 0, size);
    valid_ = size == 0 || static_cast<bool>(root_);
}

PieceTreeSource::~PieceTreeSource() = default;

bool PieceTreeSource::isValid() const noexcept { return valid_; }
uint64_t PieceTreeSource::sizeBytes() const noexcept { return subtreeBytes(root_); }
uint64_t PieceTreeSource::allocatedBytes() const noexcept {
    return added_allocated_ + countNodes(root_) * sizeof(Node);
}
uint64_t PieceTreeSource::pieceCount() const noexcept { return countNodes(root_); }
uint64_t PieceTreeSource::version() const noexcept { return version_; }

uint64_t PieceTreeSource::subtreeBytes(const std::unique_ptr<Node> &node) noexcept {
    return node ? node->subtree_bytes : 0;
}

void PieceTreeSource::updateNode(Node *node) noexcept {
    if (!node) return;
    node->subtree_bytes = node->length +
        (node->left ? node->left->subtree_bytes : 0) +
        (node->right ? node->right->subtree_bytes : 0);
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
    updatePieceNode(right.get());
    return right;
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
        updatePieceNode(root.get());
        left = std::move(a);
        right = std::move(root);
        return true;
    }
    if (offset > left_bytes + root->length) {
        std::unique_ptr<Node> a, b;
        if (!split(std::move(root->right), offset - left_bytes - root->length, a, b)) return false;
        root->right = std::move(a);
        updatePieceNode(root.get());
        left = std::move(root);
        right = std::move(b);
        return true;
    }
    if (offset == left_bytes) {
        auto old_left = std::move(root->left);
        root->left.reset();
        updatePieceNode(root.get());
        left = std::move(old_left);
        right = std::move(root);
        return true;
    }
    if (offset == left_bytes + root->length) {
        auto old_right = std::move(root->right);
        root->right.reset();
        updatePieceNode(root.get());
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
    return node ? 1 + countNodes(node->left.get()) + countNodes(node->right.get()) : 0;
}

bool PieceTreeSource::appendAdded(const uint8_t *data, uint64_t bytes,
                                  uint64_t *startOffset) noexcept {
    if (!startOffset || (bytes != 0 && !data) || (bytes & 1u) != 0) return false;
    if (bytes > memory_limit_bytes_ - std::min(memory_limit_bytes_, added_allocated_)) return false;
    *startOffset = added_size_;
    constexpr uint64_t chunkBytes = 64u * 1024u;
    uint64_t consumed = 0;
    try {
        while (consumed < bytes) {
            const uint64_t index64 = added_size_ / chunkBytes;
            const size_t index = static_cast<size_t>(index64);
            const size_t inChunk = static_cast<size_t>(added_size_ % chunkBytes);
            if (index == added_chunks_.size()) {
                if (chunkBytes > memory_limit_bytes_ -
                    std::min(memory_limit_bytes_, added_allocated_)) return false;
                auto chunk = std::unique_ptr<uint8_t[]>(new (std::nothrow) uint8_t[chunkBytes]);
                if (!chunk) return false;
                added_chunks_.push_back(std::move(chunk));
                added_allocated_ += chunkBytes;
            }
            const uint64_t count = std::min<uint64_t>(bytes - consumed, chunkBytes - inChunk);
            std::memcpy(added_chunks_[index].get() + inChunk, data + consumed,
                        static_cast<size_t>(count));
            consumed += count;
            added_size_ += count;
        }
    } catch (...) { return false; }
    return true;
}

bool PieceTreeSource::readAdded(uint64_t offset, uint32_t maxBytes,
                                TextChunkView *out) const noexcept {
    if (!out) return false;
    out->data = nullptr; out->size = 0;
    constexpr uint64_t chunkBytes = 64u * 1024u;
    if (offset > added_size_ || (offset & 1u) || maxBytes < 2u) return false;
    if (offset == added_size_) return true;
    const size_t index = static_cast<size_t>(offset / chunkBytes);
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
    const uint64_t maximumPieces = memory_limit_bytes_ / sizeof(Node);
    if (countNodes(root_.get()) + (insertedBytes ? 2u : 1u) > maximumPieces) return false;
    if (insertedBytes > memory_limit_bytes_ -
        std::min(memory_limit_bytes_, added_allocated_)) return false;

    // Allocate/append inserted bytes before mutating the tree. The only data
    // copied is the insertion itself; old pieces remain references.
    uint64_t addOffset = 0;
    if (insertedBytes && !appendAdded(static_cast<const uint8_t *>(inserted),
                                     insertedBytes, &addOffset)) return false;
    auto insertedPiece = insertedBytes
        ? makeNode(Buffer::Added, addOffset, insertedBytes) : nullptr;
    if (insertedBytes && !insertedPiece) return false;

    std::unique_ptr<Node> prefix, tail, removed, suffix;
    if (!split(std::move(root_), oldEndByte, tail, suffix) ||
        !split(std::move(tail), startByte, prefix, removed)) {
        root_ = merge(std::move(prefix), merge(std::move(removed), std::move(suffix)));
        return false;
    }
    removed.reset();
    root_ = merge(merge(std::move(prefix), std::move(insertedPiece)), std::move(suffix));
    ++version_;
    return true;
}

} // namespace windbell::largefile
