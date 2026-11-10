#include "ChunkedTextStore.h"
#include "MappedFileSource.h"
#include "FileWindowSource.h"
#include "PieceTreeSource.h"
#include "TextPartitionIndex.h"

#include <limits>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <random>
#include <utility>
#include <unistd.h>

using namespace windbell::largefile;

static void testSegmentedTextSource() {
    const char first[] = {'a', 0, 'b', 0};
    const char second[] = {'c', 0, 'd', 0};
    std::vector<TextSegment> segments{
        {first, sizeof(first)},
        {second, sizeof(second)}
    };
    SegmentedTextSource source(std::move(segments), sizeof(first) + sizeof(second));
    assert(source.isValid());
    assert(source.sizeBytes() == 8);

    TextChunkView view{};
    assert(source.read(0, 64u * 1024u, &view));
    assert(view.size == sizeof(first));
    assert(view.data[0] == 'a');
    assert(source.read(4, 64u * 1024u, &view));
    assert(view.size == sizeof(second));
    assert(view.data[0] == 'c');
    assert(source.read(8, 64u * 1024u, &view));
    assert(view.data == nullptr && view.size == 0);

    SegmentedTextSource invalid({{first, 3}}, 3);
    assert(!invalid.isValid());
    assert(!invalid.read(0, 64, &view));

    const char contiguous_bytes[] = {'e', 0, 'f', 0};
    ContiguousTextSource contiguous(contiguous_bytes, sizeof(contiguous_bytes));
    const char *contiguous_data = nullptr;
    uint64_t contiguous_size = 0;
    assert(contiguous.contiguousData(&contiguous_data, &contiguous_size));
    assert(contiguous_data == contiguous_bytes);
    assert(contiguous_size == sizeof(contiguous_bytes));
}

static void testChunkedStoreReadsAcrossBoundaries() {
    ChunkedTextStore store(128u * 1024u, 64u * 1024u);
    std::string first(64u * 1024u, 'a');
    std::string second(17u * 1024u, 'b');
    assert(store.append(first.data(), first.size()));
    assert(store.append(second.data(), second.size()));
    assert(store.sizeBytes() == first.size() + second.size());
    assert(store.allocatedBytes() == 2u * 64u * 1024u);
    store.seal();

    TextChunkView view{};
    assert(store.read(0, 64u * 1024u, &view));
    assert(view.size == 64u * 1024u);
    assert(view.data[0] == 'a');
    assert(store.read(64u * 1024u, 4096, &view));
    assert(view.size == 4096);
    assert(view.data[0] == 'b');
    assert(store.read(store.sizeBytes(), 4096, &view));
    assert(view.data == nullptr && view.size == 0);
    assert(!store.append("x", 1));
}

static void testChunkedStoreReusesAllocatedTail() {
    ChunkedTextStore store(64u * 1024u, 64u * 1024u);
    const std::string first(32u * 1024u, 'a');
    const std::string second(32u * 1024u, 'b');
    assert(store.append(first.data(), first.size()));
    assert(store.append(second.data(), second.size()));
    assert(store.allocatedBytes() == 64u * 1024u);
    assert(store.sizeBytes() == 64u * 1024u);
    store.seal();

    TextChunkView view{};
    assert(store.read(32u * 1024u, 1024, &view));
    assert(view.data[0] == 'b');
}

static void testChunkedStoreHonorsMemoryBudget() {
    ChunkedTextStore store(64u * 1024u, 64u * 1024u);
    const std::string data(64u * 1024u + 2, 'x');
    assert(!store.append(data.data(), data.size()));
    // The failed append may have filled its first chunk; size remains a valid
    // prefix and the store is intentionally discarded by the caller.
    assert(store.sizeBytes() <= store.allocatedBytes());
}

static void testMappedFileSource() {
    char path[] = "/tmp/windbell-largefile-XXXXXX";
    const int fd = ::mkstemp(path);
    assert(fd >= 0);
    const std::string expected(128u * 1024u + 36u, 'z');
    size_t written = 0;
    while (written < expected.size()) {
        const ssize_t n = ::write(fd, expected.data() + written, expected.size() - written);
        assert(n > 0);
        written += static_cast<size_t>(n);
    }
    ::close(fd);

    MappedFileSource source;
    assert(!source.isOpen());
    assert(source.open(path));
    assert(source.isOpen());
    assert(source.sizeBytes() == expected.size());
    const char *mapped_data = nullptr;
    uint64_t mapped_size = 0;
    assert(source.contiguousData(&mapped_data, &mapped_size));
    assert(mapped_data != nullptr && mapped_size == expected.size());
    assert(std::memcmp(mapped_data, expected.data(), expected.size()) == 0);

    TextChunkView view{};
    assert(source.read(64u * 1024u + 4u, 1024, &view));
    assert(view.size == 1024);
    assert(std::memcmp(view.data, expected.data() + 64u * 1024u + 5u, 1024) == 0);

    source.close();
    assert(!source.isOpen());

    FileWindowSource windowed(path, 16u * 1024u);
    assert(windowed.isOpen());
    assert(windowed.sizeBytes() == expected.size());
    assert(windowed.read(64u * 1024u + 4u, 4096, &view));
    assert(view.size == 4096);
    assert(std::memcmp(view.data, expected.data() + 64u * 1024u + 4u, 4096) == 0);
    assert(windowed.read(64u * 1024u + 8u, 128, &view));
    assert(view.size == 128);
    assert(std::memcmp(view.data, expected.data() + 64u * 1024u + 8u, 128) == 0);
    windowed.close();
    assert(!windowed.isOpen());

    ::unlink(path);
}

static void testSparseFilesFromOneToThreeHundredMegabytesUseBoundedWindow() {
    constexpr int sizes_mb[] = {1, 10, 50, 100, 200, 300};
    const char marker[] = {'O', 0, 'K', 0};
    for (const int size_mb : sizes_mb) {
        char path[] = "/tmp/windbell-largefile-stress-XXXXXX";
        const int fd = ::mkstemp(path);
        assert(fd >= 0);

        const off_t file_size = static_cast<off_t>(size_mb) * 1024 * 1024;
        assert(::ftruncate(fd, file_size) == 0);
        const off_t marker_offset = file_size - static_cast<off_t>(sizeof(marker));
        assert((marker_offset & 1) == 0);
        assert(::pwrite(fd, marker, sizeof(marker), marker_offset) ==
               static_cast<ssize_t>(sizeof(marker)));
        ::close(fd);

        // This validates large-file addressing and bounded source memory only;
        // it deliberately does not claim to parse or highlight sparse zero-filled text.
        FileWindowSource source(path, 64u * 1024u);
        assert(source.isOpen());
        assert(source.sizeBytes() == static_cast<uint64_t>(file_size));

        TextChunkView view{};
        assert(source.read(static_cast<uint64_t>(marker_offset), 64u * 1024u, &view));
        assert(view.size == sizeof(marker));
        assert(std::memcmp(view.data, marker, sizeof(marker)) == 0);

        source.close();
        ::unlink(path);
    }
}


static void testPieceTreeHandlesThreeHundredMegabytesWithoutMaterializingDocument() {
    char path[] = "/tmp/windbell-piece-300mb-XXXXXX";
    const int fd = ::mkstemp(path);
    assert(fd >= 0);
    constexpr uint64_t documentBytes = 300ULL * 1024ULL * 1024ULL;
    assert((documentBytes & 1u) == 0);
    assert(::ftruncate(fd, static_cast<off_t>(documentBytes)) == 0);
    const char marker[] = {'O', 0, 'K', 0};
    assert(::pwrite(fd, marker, sizeof(marker),
                    static_cast<off_t>(documentBytes - sizeof(marker))) ==
           static_cast<ssize_t>(sizeof(marker)));
    ::close(fd);

    auto window = std::make_shared<FileWindowSource>(path, 64u * 1024u);
    assert(window->isOpen());
    PieceTreeSource source(
        std::static_pointer_cast<const TextSource>(window), 2u * 1024u * 1024u);
    assert(source.isValid());
    assert(source.sizeBytes() == documentBytes);
    assert(source.pieceCount() == 1);
    assert(source.allocatedBytes() < 64u * 1024u);

    TextChunkView view{};
    assert(source.read(documentBytes - sizeof(marker), 64u * 1024u, &view));
    assert(view.size == sizeof(marker));
    assert(std::memcmp(view.data, marker, sizeof(marker)) == 0);

    // A small tail edit must allocate only a new piece and append chunk; the
    // 300 MiB unchanged backing file remains a bounded pread() source.
    const char inserted[] = {'!', 0};
    assert(source.replace(documentBytes, documentBytes, inserted, sizeof(inserted)));
    assert(source.sizeBytes() == documentBytes + sizeof(inserted));
    assert(source.pieceCount() <= 3);
    assert(source.allocatedBytes() < 256u * 1024u);
    assert(source.read(documentBytes, 64u, &view));
    assert(view.size == sizeof(inserted));
    assert(std::memcmp(view.data, inserted, sizeof(inserted)) == 0);

    window.reset();
    ::unlink(path);
}

static std::string readAll(TextSource &source) {
    std::string result;
    uint64_t offset = 0;
    while (offset < source.sizeBytes()) {
        TextChunkView view{};
        assert(source.read(offset, 64u * 1024u, &view));
        assert(view.data && view.size > 0);
        result.append(view.data, view.size);
        offset += view.size;
    }
    return result;
}

static void testPieceTreeEditsDoNotCopyUnchangedText() {
    char path[] = "/tmp/windbell-piece-tree-XXXXXX";
    const int fd = ::mkstemp(path);
    assert(fd >= 0);
    std::string original;
    original.reserve(2u * 1024u * 1024u);
    for (int i = 0; i < 1024; ++i) {
        original += "line-";
        original += std::to_string(i);
        original += "\n";
    }
    std::string utf16;
    utf16.reserve(original.size() * 2);
    for (unsigned char c : original) { utf16.push_back(static_cast<char>(c)); utf16.push_back(0); }
    assert(::write(fd, utf16.data(), utf16.size()) == static_cast<ssize_t>(utf16.size()));
    ::close(fd);

    auto mapped = std::make_shared<MappedFileSource>(path);
    assert(mapped->isOpen());
    PieceTreeSource source(mapped, 2u * 1024u * 1024u);
    assert(source.isValid());
    assert(source.sizeBytes() == utf16.size());
    const char *tree_data = nullptr;
    uint64_t tree_size = 0;
    assert(source.contiguousData(&tree_data, &tree_size));
    assert(tree_data != nullptr && tree_size == utf16.size());
    const uint64_t beforePieces = source.pieceCount();
    const char insert[] = {'X', 0, 'Y', 0};
    assert(source.replace(0, 0, insert, sizeof(insert)));
    assert(source.version() == 1);
    assert(!source.contiguousData(&tree_data, &tree_size));
    assert(source.sizeBytes() == utf16.size() + sizeof(insert));
    assert(source.pieceCount() <= beforePieces + 2);
    assert(source.allocatedBytes() < 256u * 1024u);

    // An insertion rejected by the document memory budget must not consume
    // append-arena capacity or alter the visible source/version.
    const uint64_t beforeFailedSize = source.sizeBytes();
    const uint64_t beforeFailedAllocation = source.allocatedBytes();
    const uint64_t beforeFailedVersion = source.version();
    std::vector<char> oversized(3u * 1024u * 1024u, 0);
    assert(!source.replace(0, 0, oversized.data(), oversized.size()));
    assert(source.sizeBytes() == beforeFailedSize);
    assert(source.allocatedBytes() == beforeFailedAllocation);
    assert(source.version() == beforeFailedVersion);

    TextChunkView view{};
    assert(source.read(0, 64, &view));
    assert(view.size >= sizeof(insert));
    assert(std::memcmp(view.data, insert, sizeof(insert)) == 0);

    const uint64_t removeStart = 2u * 10u;
    assert(source.replace(removeStart, removeStart + 2u * 5u, nullptr, 0));
    assert(source.version() == 2);
    assert(source.sizeBytes() == utf16.size() + sizeof(insert) - 10u);
    const char appended[] = {'Z', 0};
    assert(source.replace(source.sizeBytes(), source.sizeBytes(), appended, sizeof(appended)));
    assert(source.version() == 3);
    assert(source.sizeBytes() == utf16.size() + sizeof(insert) - 8u);

    ::unlink(path);
}



static void testPieceTreeUsesBoundedFileWindowAfterUnlink() {
    char path[] = "/tmp/windbell-piece-window-XXXXXX";
    const int fd = ::mkstemp(path);
    assert(fd >= 0);
    std::string initial;
    for (int i = 0; i < 80u * 1024u; ++i) {
        initial.push_back(static_cast<char>('a' + (i % 26)));
        initial.push_back(0);
    }
    assert(::write(fd, initial.data(), initial.size()) ==
           static_cast<ssize_t>(initial.size()));
    ::close(fd);

    auto window = std::make_shared<FileWindowSource>(path, 64u * 1024u);
    assert(window->isOpen());
    assert(window->sizeBytes() == initial.size());
    // Native JNI unlinks the backing path once the descriptor is open.
    assert(::unlink(path) == 0);
    PieceTreeSource source(
        std::static_pointer_cast<const TextSource>(window), 2u * 1024u * 1024u);
    assert(source.isValid());
    assert(source.sizeBytes() == initial.size());

    TextChunkView view{};
    assert(source.read(64u * 1024u - 8u, 64u, &view));
    assert(view.size == 64u);
    assert(std::memcmp(view.data, initial.data() + 64u * 1024u - 8u, 64u) == 0);

    const char inserted[] = {'X', 0, 'Y', 0};
    assert(source.replace(64u * 1024u, 64u * 1024u, inserted, sizeof(inserted)));
    std::string expected = initial;
    expected.insert(64u * 1024u, inserted, sizeof(inserted));
    assert(source.sizeBytes() == expected.size());
    assert(readAll(source) == expected);
}

static void testCopyTextRangeAcrossPieceBoundaries() {
    char path[] = "/tmp/windbell-copy-range-XXXXXX";
    const int fd = ::mkstemp(path);
    assert(fd >= 0);
    const char initial[] = {'a',0,'b',0,'c',0,'d',0};
    assert(::write(fd, initial, sizeof(initial)) == static_cast<ssize_t>(sizeof(initial)));
    ::close(fd);
    auto mapped = std::make_shared<MappedFileSource>(path);
    PieceTreeSource source(mapped, 1024u * 1024u);
    const char insert[] = {'X',0,'Y',0};
    assert(source.replace(4, 4, insert, sizeof(insert)));
    std::string copied(8, '\0');
    assert(copyTextRange(source, 2, copied.size(), copied.data()));
    const std::string expected = std::string(initial, sizeof(initial)).substr(2, 2) +
        std::string(insert, sizeof(insert)) + std::string(initial, sizeof(initial)).substr(4, 2);
    assert(copied == expected);
    assert(!copyTextRange(source, 1, 2, copied.data()));
    assert(!copyTextRange(source, 0, source.sizeBytes() + 2, copied.data()));
    ::unlink(path);
}

static void testPieceTreeSnapshotIsolatedAndSharesBacking() {
    char path[] = "/tmp/windbell-piece-snapshot-XXXXXX";
    const int fd = ::mkstemp(path);
    assert(fd >= 0);
    const char initial[] = {'a',0,'b',0,'c',0,'d',0};
    assert(::write(fd, initial, sizeof(initial)) == static_cast<ssize_t>(sizeof(initial)));
    ::close(fd);

    auto mapped = std::make_shared<MappedFileSource>(path);
    PieceTreeSource original(mapped, 1024u * 1024u);
    const char firstInsert[] = {'X',0};
    assert(original.replace(2, 2, firstInsert, sizeof(firstInsert)));
    const uint64_t beforeSnapshotBytes = original.allocatedBytes();

    // Many simultaneous snapshots must share the same persistent treap rather
    // than allocate one complete copy of every piece node per document version.
    std::vector<std::unique_ptr<PieceTreeSource>> snapshots;
    snapshots.reserve(32);
    for (int i = 0; i < 32; ++i) {
        auto candidate = original.clone();
        assert(candidate && candidate->isValid());
        assert(candidate->allocatedBytes() == beforeSnapshotBytes);
        snapshots.push_back(std::move(candidate));
    }
    auto snapshot = std::move(snapshots.back());
    snapshots.pop_back();
    const std::string snapshotBytes = readAll(*snapshot);

    const char secondInsert[] = {'Y',0};
    assert(snapshot->replace(4, 4, secondInsert, sizeof(secondInsert)));
    assert(readAll(original) == snapshotBytes);
    assert(readAll(*snapshot) != snapshotBytes);
    assert(snapshot->version() == original.version() + 1);
    assert(snapshot->allocatedBytes() > original.allocatedBytes());
    ::unlink(path);
}

static void testPieceTreeRandomEditsMatchReferenceBuffer() {
    char path[] = "/tmp/windbell-piece-random-XXXXXX";
    const int fd = ::mkstemp(path);
    assert(fd >= 0);
    std::string initial;
    for (int i = 0; i < 4096; ++i) {
        initial.push_back(static_cast<char>('a' + (i % 26)));
        initial.push_back(0);
    }
    assert(::write(fd, initial.data(), initial.size()) == static_cast<ssize_t>(initial.size()));
    ::close(fd);

    auto mapped = std::make_shared<MappedFileSource>(path);
    assert(mapped->isOpen());
    PieceTreeSource source(mapped, 8u * 1024u * 1024u);
    assert(source.isValid());
    std::string expected = initial;
    std::mt19937 random(0x57494E44u);
    for (int iteration = 0; iteration < 750; ++iteration) {
        const uint64_t units = expected.size() / 2u;
        const uint64_t a = units == 0 ? 0 : random() % (units + 1u);
        const uint64_t b = a + (units == a ? 0 : random() % (units - a + 1u));
        const uint64_t start = a * 2u;
        const uint64_t end = b * 2u;
        const uint16_t value = static_cast<uint16_t>('A' + (iteration % 26));
        const char inserted[2] = {
            static_cast<char>(value & 0xffu),
            static_cast<char>((value >> 8u) & 0xffu)
        };
        assert(source.replace(start, end, inserted, sizeof(inserted)));
        expected.replace(static_cast<size_t>(start),
                         static_cast<size_t>(end - start),
                         inserted, sizeof(inserted));
        assert(source.sizeBytes() == expected.size());
        assert(readAll(source) == expected);
        assert(source.allocatedBytes() <= 8u * 1024u * 1024u);
    }
    ::unlink(path);
}


static std::string makeUtf16Lines(uint32_t count) {
    std::string bytes;
    for (uint32_t i = 0; i < count; ++i) {
        const std::string line = "line-" + std::to_string(i) + "\n";
        for (unsigned char c : line) { bytes.push_back(static_cast<char>(c)); bytes.push_back(0); }
    }
    return bytes;
}

static uint64_t utf16LineStart(const std::string &bytes, uint64_t line) {
    uint64_t current = 0;
    for (uint64_t offset = 0; offset + 1 < bytes.size(); offset += 2) {
        if (current == line) return offset;
        if (bytes[offset] == '\n' && bytes[offset + 1] == 0) ++current;
    }
    return bytes.size();
}

static void testSparsePartitionIndexSurvivesEdits() {
    char path[] = "/tmp/windbell-partition-index-XXXXXX";
    const int fd = ::mkstemp(path);
    assert(fd >= 0);
    std::string expected = makeUtf16Lines(700);
    assert(::write(fd, expected.data(), expected.size()) == static_cast<ssize_t>(expected.size()));
    ::close(fd);

    auto mapped = std::make_shared<MappedFileSource>(path);
    assert(mapped->isOpen());
    PieceTreeSource source(mapped, 4u * 1024u * 1024u);
    TextPartitionIndex index(8);
    uint64_t line = 0, byte = 0;
    assert(index.lineForByte(source, utf16LineStart(expected, 500), &line));
    assert(line == 500);
    assert(index.byteForLine(source, 640, &byte));
    assert(byte == utf16LineStart(expected, 640));

    const uint64_t start = utf16LineStart(expected, 100);
    const uint64_t end = utf16LineStart(expected, 110);
    const char inserted[] = {
        'n',0,'e',0,'w',0,'-',0,'a',0,'\n',0,
        'n',0,'e',0,'w',0,'-',0,'b',0,'\n',0
    };
    assert(index.applyEdit(source, start, end, inserted, sizeof(inserted)));
    assert(source.replace(start, end, inserted, sizeof(inserted)));
    expected.replace(static_cast<size_t>(start), static_cast<size_t>(end - start),
                     inserted, sizeof(inserted));

    const uint64_t expectedLine500 = utf16LineStart(expected, 492);
    assert(index.byteForLine(source, 492, &byte));
    assert(byte == expectedLine500);
    assert(index.lineForByte(source, expectedLine500, &line));
    assert(line == 492);
    assert(index.version() >= 2);

    // Exercise every row after a line-count-changing edit. lineForByte must
    // return the row counted during its single bounded scan, including exact
    // checkpoint boundaries and EOF.
    const uint64_t resultingLines = 692;
    for (uint64_t expectedLine = 0; expectedLine < resultingLines; ++expectedLine) {
        const uint64_t lineStart = utf16LineStart(expected, expectedLine);
        uint64_t resolvedLineStart = UINT64_MAX;
        assert(index.pointForByte(source, lineStart, &line, &resolvedLineStart));
        assert(line == expectedLine);
        assert(resolvedLineStart == lineStart);
        assert(index.byteForLine(source, expectedLine, &byte));
        assert(byte == lineStart);
    }
    assert(index.lineForByte(source, source.sizeBytes(), &line));
    assert(line == resultingLines);

    // Random-access lookups must not append earlier checkpoints after later
    // checkpoints. That would invalidate upper_bound() and corrupt later rows.
    for (uint64_t expectedLine = resultingLines; expectedLine-- > 0;) {
        const uint64_t lineStart = utf16LineStart(expected, expectedLine);
        assert(index.pointForByte(source, lineStart, &line, &byte));
        assert(line == expectedLine);
        assert(byte == lineStart);
    }
    const auto &checkpoints = index.checkpoints();
    for (size_t i = 1; i < checkpoints.size(); ++i) {
        assert(checkpoints[i - 1].byte_offset < checkpoints[i].byte_offset);
        assert(checkpoints[i - 1].line < checkpoints[i].line);
    }
    ::unlink(path);
}

class HugeLogicalTextSource final : public TextSource {
public:
    uint64_t sizeBytes() const noexcept override {
        return std::numeric_limits<uint64_t>::max() - 1u;
    }
    bool read(uint64_t, uint32_t, TextChunkView *out) const noexcept override {
        if (out) { out->data = nullptr; out->size = 0; }
        return false;
    }
};

static void testPieceTreeRejectsDocumentSizeOverflow() {
    auto backing = std::make_shared<HugeLogicalTextSource>();
    PieceTreeSource source(
        std::static_pointer_cast<const TextSource>(backing), 1024u * 1024u);
    assert(source.isValid());
    const uint64_t beforeSize = source.sizeBytes();
    const uint64_t beforeVersion = source.version();
    const uint64_t beforeAllocation = source.allocatedBytes();
    const char inserted[] = {'X', 0};
    assert(!source.replace(beforeSize, beforeSize, inserted, sizeof(inserted)));
    assert(source.sizeBytes() == beforeSize);
    assert(source.version() == beforeVersion);
    assert(source.allocatedBytes() == beforeAllocation);
}

int main() {
    testPieceTreeRejectsDocumentSizeOverflow();
    testSegmentedTextSource();
    testChunkedStoreReadsAcrossBoundaries();
    testChunkedStoreReusesAllocatedTail();
    testChunkedStoreHonorsMemoryBudget();
    testMappedFileSource();
    testPieceTreeEditsDoNotCopyUnchangedText();
    testPieceTreeRandomEditsMatchReferenceBuffer();
    testPieceTreeSnapshotIsolatedAndSharesBacking();
    testPieceTreeUsesBoundedFileWindowAfterUnlink();
    testPieceTreeHandlesThreeHundredMegabytesWithoutMaterializingDocument();
    testCopyTextRangeAcrossPieceBoundaries();
    testSparsePartitionIndexSurvivesEdits();
    testSparseFilesFromOneToThreeHundredMegabytesUseBoundedWindow();
    return 0;
}
