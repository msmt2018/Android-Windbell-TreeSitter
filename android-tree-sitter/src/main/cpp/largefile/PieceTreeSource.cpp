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
    uint32_t subtree_height;
    std::shared_ptr<Node> left;
    std::shared_ptr<Node> right;

    Node(Buffer b, uint64_t o, uint64_t n, uint64_t p) noexcept
        : buffer(b), source_offset(o), length(n), subtree_bytes(n),
          subtree_pieces(1), priority(p), subtree_height(1) {}
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

PieceTreeSource::PieceTreeSource(std::shared_ptr<const TextSource> original,
                                 uint64_t memoryLimitBytes, SnapshotTag) noexcept
    : original_(std::move(original)), memory_limit_bytes_(memoryLimitBytes), valid_(true) {}

PieceTreeSource::~PieceTreeSource() = default;

std::unique_ptr<PieceTreeSource> PieceTreeSource::clone() const noexcept {
    auto copy = std::unique_ptr<PieceTreeSource>(
        new (std::nothrow) PieceTreeSource(original_, memory_limit_bytes_, SnapshotTag{}));
    if (!copy) return nullptr;
    // The treap is persistent. Only the root reference is copied; all unchanged
    // subtrees remain shared and become private only along a later edit path.
    copy->root_ = root_;
    try {
        copy->added_chunks_ = added_chunks_;
    } catch (...) {
        return nullptr;
    }
    copy->added_allocated_ = added_allocated_;
    copy->added_size_ = added_size_;
    constexpr uint64_t chunkBytes = 64u * 1024u;
    if ((copy->added_size_ % chunkBytes) != 0) {
        const uint64_t padding = chunkBytes - (copy->added_size_ % chunkBytes);
        if (padding > copy->added_allocated_ - copy->added_size_) return nullptr;
        copy->added_size_ += padding;
    }
    copy->version_ = version_;
    copy->random_state_ = random_state_;
    copy->valid_ = valid_;
    return copy;
}

bool PieceTreeSource::isValid() const noexcept { return valid_; }
uint64_t PieceTreeSource::sizeBytes() const noexcept { return subtreeBytes(root_); }

bool PieceTreeSource::contiguousData(
    const char **data, uint64_t *sizeBytes
) const noexcept {
    if (!data || !sizeBytes || !valid_) return false;
    *data = nullptr;
    *sizeBytes = sizeBytes();
    if (*sizeBytes == 0) return true;

    // A single untouched original piece is already contiguous in its backing
    // source. Edited/multi-piece trees deliberately do not flatten here.
    if (!root_ || root_->left || root_->right ||
        root_->buffer != Buffer::Original ||
        root_->length != *sizeBytes) return false;

    const char *original_data = nullptr;
    uint64_t original_size = 0;
    if (!original_->contiguousData(&original_data, &original_size) ||
        !original_data || root_->source_offset > original_size ||
        root_->length > original_size - root_->source_offset) return false;
    *data = original_data + static_cast<size_t>(root_->source_offset);
    return true;
}

uint64_t PieceTreeSource::allocatedBytes() const noexcept {
    const uint64_t nodes = countNodes(root_.get());
    if (nodes > (std::numeric_limits<uint64_t>::max() - added_allocated_) / sizeof(Node))
        return std::numeric_limits<uint64_t>::max();
    return added_allocated_ + nodes * sizeof(Node);
}

uint64_t PieceTreeSource::pieceCount() const noexcept { return countNodes(root_.get()); }
uint64_t PieceTreeSource::version() const noexcept { return version_; }

uint64_t PieceTreeSource::subtreeBytes(const std::shared_ptr<Node> &node) noexcept {
    return node ? node->subtree_bytes : 0;
}

void PieceTreeSource::updateNode(Node *node) noexcept {
    if (!node) return;
    const uint64_t leftBytes = node->left ? node->left->subtree_bytes : 0;
    const uint64_t rightBytes = node->right ? node->right->subtree_bytes : 0;
    node->subtree_bytes = node->length + leftBytes + rightBytes;
    node->subtree_pieces = 1 +
        (node->left ? node->left->subtree_pieces : 0) +
        (node->right ? node->right->subtree_pieces : 0);
    node->subtree_height = 1u + std::max(
        node->left ? node->left->subtree_height : 0u,
        node->right ? node->right->subtree_height : 0u);
}

std::shared_ptr<PieceTreeSource::Node> PieceTreeSource::cloneShallow(
    const std::shared_ptr<Node> &node) noexcept {
    if (!node) return nullptr;
    Node *raw = new (std::nothrow) Node(
        node->buffer, node->source_offset, node->length, node->priority);
    if (!raw) return nullptr;
    try {
        std::shared_ptr<Node> copy(raw);
        copy->subtree_bytes = node->subtree_bytes;
        copy->subtree_pieces = node->subtree_pieces;
        copy->subtree_height = node->subtree_height;
        copy->left = node->left;
        copy->right = node->right;
        return copy;
    } catch (...) {
        // shared_ptr's raw-pointer constructor deletes raw if control-block
        // allocation fails; deleting it again here would double-free.
        return nullptr;
    }
}

bool PieceTreeSource::merge(std::shared_ptr<Node> left,
                            std::shared_ptr<Node> right,
                            std::shared_ptr<Node> *out) noexcept {
    if (!out) return false;
    if (!left) { *out = std::move(right); return true; }
    if (!right) { *out = std::move(left); return true; }

    if (left->priority > right->priority) {
        auto root = cloneShallow(left);
        if (!root) return false;
        std::shared_ptr<Node> merged;
        if (!merge(left->right, std::move(right), &merged)) return false;
        root->right = std::move(merged);
        updateNode(root.get());
        *out = std::move(root);
        return true;
    }

    auto root = cloneShallow(right);
    if (!root) return false;
    std::shared_ptr<Node> merged;
    if (!merge(std::move(left), right->left, &merged)) return false;
    root->left = std::move(merged);
    updateNode(root.get());
    *out = std::move(root);
    return true;
}

std::shared_ptr<PieceTreeSource::Node> PieceTreeSource::makeNode(
    Buffer buffer, uint64_t offset, uint64_t length) noexcept {
    if (length == 0) return nullptr;
    random_state_ ^= random_state_ >> 12;
    random_state_ ^= random_state_ << 25;
    random_state_ ^= random_state_ >> 27;
    const uint64_t priority = random_state_ * 0x2545F4914F6CDD1DULL;
    Node *raw = new (std::nothrow) Node(buffer, offset, length, priority);
    if (!raw) return nullptr;
    try {
        return std::shared_ptr<Node>(raw);
    } catch (...) {
        // shared_ptr's raw-pointer constructor deletes raw if control-block
        // allocation fails; deleting it again here would double-free.
        return nullptr;
    }
}

bool PieceTreeSource::split(std::shared_ptr<Node> root, uint64_t offset,
                            std::shared_ptr<Node> &left,
                            std::shared_ptr<Node> &right) noexcept {
    if (!root) {
        if (offset != 0) return false;
        left.reset();
        right.reset();
        return true;
    }

    const uint64_t leftBytes = subtreeBytes(root->left);
    const uint64_t pieceEnd = leftBytes + root->length;
    if (offset < leftBytes) {
        std::shared_ptr<Node> a, b;
        if (!split(root->left, offset, a, b)) return false;
        auto copy = cloneShallow(root);
        if (!copy) return false;
        copy->left = std::move(b);
        updateNode(copy.get());
        left = std::move(a);
        right = std::move(copy);
        return true;
    }
    if (offset > pieceEnd) {
        std::shared_ptr<Node> a, b;
        if (!split(root->right, offset - pieceEnd, a, b)) return false;
        auto copy = cloneShallow(root);
        if (!copy) return false;
        copy->right = std::move(a);
        updateNode(copy.get());
        left = std::move(copy);
        right = std::move(b);
        return true;
    }
    if (offset == leftBytes) {
        auto copy = cloneShallow(root);
        if (!copy) return false;
        copy->left.reset();
        updateNode(copy.get());
        left = root->left;
        right = std::move(copy);
        return true;
    }
    if (offset == pieceEnd) {
        auto copy = cloneShallow(root);
        if (!copy) return false;
        copy->right.reset();
        updateNode(copy.get());
        left = std::move(copy);
        right = root->right;
        return true;
    }

    const uint64_t inPiece = offset - leftBytes;
    auto leftPiece = makeNode(root->buffer, root->source_offset, inPiece);
    auto rightPiece = makeNode(root->buffer, root->source_offset + inPiece,
                               root->length - inPiece);
    if (!leftPiece || !rightPiece) return false;

    std::shared_ptr<Node> leftTree, rightTree;
    if (!merge(root->left, std::move(leftPiece), &leftTree) ||
        !merge(std::move(rightPiece), root->right, &rightTree)) return false;
    left = std::move(leftTree);
    right = std::move(rightTree);
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
        std::min(memory_limit_bytes_, added_allocated_)) / chunkBytes ||
        requiredChunks > static_cast<uint64_t>(std::numeric_limits<size_t>::max()) ||
        requiredChunks > static_cast<uint64_t>(std::numeric_limits<size_t>::max() -
                                               added_chunks_.size())) return false;

    // Allocate all chunks and vector capacity before touching logical size or
    // existing tail bytes. A failed append is observationally atomic.
    const size_t requiredChunkCount = static_cast<size_t>(requiredChunks);
    std::vector<std::shared_ptr<uint8_t>> pending;
    try {
        pending.reserve(requiredChunkCount);
        added_chunks_.reserve(added_chunks_.size() + requiredChunkCount);
        for (size_t i = 0; i < requiredChunkCount; ++i) {
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
    out->data = nullptr;
    out->size = 0;
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
    out->data = nullptr;
    out->size = 0;
    const uint64_t total = sizeBytes();
    if (!valid_ || (offset & 1u) || offset > total || maxBytes < 2u) return false;
    if (offset == total) return true;

    std::shared_ptr<Node> node = root_;
    uint64_t remaining = offset;
    while (node) {
        const uint64_t leftBytes = subtreeBytes(node->left);
        if (remaining < leftBytes) {
            node = node->left;
            continue;
        }
        if (remaining < leftBytes + node->length)
            return readPiece(node.get(), remaining - leftBytes, maxBytes, out);
        remaining -= leftBytes + node->length;
        node = node->right;
    }
    return false;
}

bool PieceTreeSource::replace(uint64_t startByte, uint64_t oldEndByte,
                              const void *inserted, uint64_t insertedBytes) noexcept {
    if (!valid_ || (startByte & 1u) || (oldEndByte & 1u) ||
        (insertedBytes & 1u) || oldEndByte < startByte ||
        oldEndByte > sizeBytes() || (insertedBytes != 0 && !inserted)) return false;

    // Reject document-size overflow before allocating tree paths or append
    // chunks. Source offsets are 64-bit even though the Tree-sitter JNI ABI
    // currently has a narrower boundary.
    const uint64_t currentBytes = sizeBytes();
    const uint64_t removedBytes = oldEndByte - startByte;
    const uint64_t retainedBytes = currentBytes - removedBytes;
    if (insertedBytes > std::numeric_limits<uint64_t>::max() - retainedBytes)
        return false;

    constexpr uint64_t chunkBytes = 64u * 1024u;
    const uint64_t tailCapacity = added_allocated_ - added_size_;
    const uint64_t bytesBeyondTail = insertedBytes > tailCapacity
        ? insertedBytes - tailCapacity : 0;
    if (bytesBeyondTail > std::numeric_limits<uint64_t>::max() - (chunkBytes - 1u))
        return false;
    const uint64_t requiredChunks = bytesBeyondTail == 0 ? 0 :
        (bytesBeyondTail + chunkBytes - 1u) / chunkBytes;
    if (requiredChunks > std::numeric_limits<uint64_t>::max() / chunkBytes) return false;
    const uint64_t additionalChunks = requiredChunks * chunkBytes;
    if (additionalChunks > memory_limit_bytes_ -
        std::min(memory_limit_bytes_, added_allocated_) ||
        additionalChunks > std::numeric_limits<uint64_t>::max() - added_allocated_) return false;
    const uint64_t projectedAdded = added_allocated_ + additionalChunks;
    const uint64_t maximumPieces =
        (memory_limit_bytes_ - projectedAdded) / sizeof(Node);
    const uint64_t currentPieces = countNodes(root_.get());
    const uint64_t splitNodes = insertedBytes ? 3u : 2u;
    const uint64_t height = root_ ? root_->subtree_height : 0u;
    const uint64_t transientNodes = height > (std::numeric_limits<uint64_t>::max() - 16u) / 8u
        ? std::numeric_limits<uint64_t>::max() : 8u * height + 16u;
    if (currentPieces > maximumPieces ||
        splitNodes > maximumPieces - currentPieces ||
        transientNodes > maximumPieces - currentPieces - splitNodes) return false;

    // Build a persistent candidate without mutating the live root. Every split
    // and merge path is copied; any allocation failure discards only the
    // candidate and leaves the current document untouched.
    const uint64_t addOffset = added_size_;
    auto insertedPiece = insertedBytes
        ? makeNode(Buffer::Added, addOffset, insertedBytes) : nullptr;
    if (insertedBytes && !insertedPiece) return false;

    std::shared_ptr<Node> prefix, tail, removed, suffix;
    if (!split(root_, oldEndByte, tail, suffix) ||
        !split(std::move(tail), startByte, prefix, removed)) return false;

    std::shared_ptr<Node> prefixAndInsert;
    std::shared_ptr<Node> candidate;
    if (!merge(std::move(prefix), std::move(insertedPiece), &prefixAndInsert) ||
        !merge(std::move(prefixAndInsert), std::move(suffix), &candidate)) return false;

    // Append only after all tree allocations have succeeded. appendAdded()
    // itself is failure-atomic, so the live root and arena commit together.
    uint64_t committedOffset = 0;
    if (insertedBytes && !appendAdded(static_cast<const uint8_t *>(inserted),
                                     insertedBytes, &committedOffset)) return false;
    if (candidate && insertedBytes) {
        // insertedPiece's offset was based on the pre-append tail and remains
        // stable because the append arena is append-only within this version.
        (void)committedOffset;
    }
    root_ = std::move(candidate);
    ++version_;
    return true;
}

} // namespace windbell::largefile
