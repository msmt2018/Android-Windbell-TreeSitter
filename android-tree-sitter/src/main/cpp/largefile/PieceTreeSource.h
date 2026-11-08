#pragma once

#include "TextSource.h"

#include <cstdint>
#include <memory>
#include <vector>

namespace windbell::largefile {

/**
 * Persistent editable UTF-16LE piece treap.
 *
 * The original source and immutable append chunks are shared by snapshots.
 * Tree nodes are immutable across snapshots: edits path-copy only the search,
 * split and merge paths. Unchanged document bytes and unchanged tree branches
 * are never copied. All offsets are bytes; callers serialize access to a
 * mutable PieceTreeSource instance.
 */
class PieceTreeSource final : public TextSource {
public:
    explicit PieceTreeSource(std::shared_ptr<const TextSource> original,
                             uint64_t memoryLimitBytes = 64u * 1024u * 1024u) noexcept;
    ~PieceTreeSource() override;

    PieceTreeSource(const PieceTreeSource &) = delete;
    PieceTreeSource &operator=(const PieceTreeSource &) = delete;

    bool isValid() const noexcept;
    uint64_t sizeBytes() const noexcept override;
    bool read(uint64_t offset, uint32_t maxBytes, TextChunkView *out) const noexcept override;

    // Replace [startByte, oldEndByte) with UTF-16LE bytes.
    // O(log(piece-count)) tree work plus inserted bytes, excluding source reads.
    bool replace(uint64_t startByte, uint64_t oldEndByte,
                 const void *inserted, uint64_t insertedBytes) noexcept;

    // O(1) tree snapshot: unchanged nodes, source and sealed append chunks share
    // ownership. A partial append tail is sealed so snapshots cannot overwrite
    // bytes referenced by another version.
    std::unique_ptr<PieceTreeSource> clone() const noexcept;

    uint64_t allocatedBytes() const noexcept;
    uint64_t pieceCount() const noexcept;
    uint64_t version() const noexcept;

private:
    enum class Buffer : uint8_t { Original, Added };
    struct Node;

    static uint64_t subtreeBytes(const std::shared_ptr<Node> &node) noexcept;
    static uint64_t countNodes(const Node *node) noexcept;
    static void updateNode(Node *node) noexcept;
    static std::shared_ptr<Node> cloneShallow(
        const std::shared_ptr<Node> &node) noexcept;
    static bool merge(std::shared_ptr<Node> left,
                      std::shared_ptr<Node> right,
                      std::shared_ptr<Node> *out) noexcept;
    bool split(std::shared_ptr<Node> root, uint64_t offset,
               std::shared_ptr<Node> &left,
               std::shared_ptr<Node> &right) noexcept;

    bool appendAdded(const uint8_t *data, uint64_t bytes,
                     uint64_t *startOffset) noexcept;
    bool readAdded(uint64_t offset, uint32_t maxBytes,
                   TextChunkView *out) const noexcept;
    bool readPiece(const Node *piece, uint64_t localOffset,
                   uint32_t maxBytes, TextChunkView *out) const noexcept;
    std::shared_ptr<Node> makeNode(Buffer buffer, uint64_t offset,
                                   uint64_t length) noexcept;

    std::shared_ptr<const TextSource> original_;
    std::shared_ptr<Node> root_;
    std::vector<std::shared_ptr<uint8_t>> added_chunks_;
    uint64_t added_size_ = 0;
    uint64_t added_allocated_ = 0;
    uint64_t memory_limit_bytes_;
    uint64_t version_ = 0;
    uint64_t random_state_ = 0x9E3779B97F4A7C15ULL;
    bool valid_ = false;
};

} // namespace windbell::largefile
