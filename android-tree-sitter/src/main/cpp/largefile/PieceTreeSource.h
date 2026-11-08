#pragma once

#include "TextSource.h"

#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace windbell::largefile {

/**
 * Editable UTF-16LE piece tree.
 *
 * The original source is immutable (normally a read-only mmap). Insertions are
 * appended to stable 64 KiB chunks. Replacements split/merge an implicit treap
 * of pieces; unchanged source bytes are never copied. All offsets are bytes.
 * Synchronize externally when a document is shared between threads.
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
    // O(log(piece-count) + inserted bytes); the unchanged prefix/suffix are
    // represented by existing pieces and are not copied.
    bool replace(uint64_t startByte, uint64_t oldEndByte,
                 const void *inserted, uint64_t insertedBytes) noexcept;

    uint64_t allocatedBytes() const noexcept;
    uint64_t pieceCount() const noexcept;
    uint64_t version() const noexcept;

private:
    enum class Buffer : uint8_t { Original, Added };
    struct Node;

    static uint64_t subtreeBytes(const std::unique_ptr<Node> &node) noexcept;
    static std::unique_ptr<Node> merge(std::unique_ptr<Node> left,
                                       std::unique_ptr<Node> right) noexcept;
    static void updateNode(Node *node) noexcept;
    bool split(std::unique_ptr<Node> root, uint64_t offset,
                      std::unique_ptr<Node> &left,
                      std::unique_ptr<Node> &right) noexcept;
    static uint64_t countNodes(const Node *node) noexcept;

    bool appendAdded(const uint8_t *data, uint64_t bytes,
                     uint64_t *startOffset) noexcept;
    bool readAdded(uint64_t offset, uint32_t maxBytes,
                   TextChunkView *out) const noexcept;
    bool readPiece(const Node *piece, uint64_t localOffset,
                   uint32_t maxBytes, TextChunkView *out) const noexcept;
    std::unique_ptr<Node> makeNode(Buffer buffer, uint64_t offset,
                                   uint64_t length) noexcept;

    std::shared_ptr<const TextSource> original_;
    std::unique_ptr<Node> root_;
    std::vector<std::unique_ptr<uint8_t[]>> added_chunks_;
    uint64_t added_size_ = 0;
    uint64_t added_allocated_ = 0;
    uint64_t memory_limit_bytes_;
    uint64_t version_ = 0;
    uint64_t random_state_ = 0x9E3779B97F4A7C15ULL;
    bool valid_ = false;
};

} // namespace windbell::largefile
