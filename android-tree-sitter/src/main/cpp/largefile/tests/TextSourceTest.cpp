#include "ChunkedTextStore.h"
#include "MappedFileSource.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
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

    TextChunkView view{};
    assert(source.read(64u * 1024u + 4u, 1024, &view));
    assert(view.size == 1024);
    assert(std::memcmp(view.data, expected.data() + 64u * 1024u + 5u, 1024) == 0);

    source.close();
    assert(!source.isOpen());
    ::unlink(path);
}

int main() {
    testSegmentedTextSource();
    testChunkedStoreReadsAcrossBoundaries();
    testChunkedStoreReusesAllocatedTail();
    testChunkedStoreHonorsMemoryBudget();
    testMappedFileSource();
    return 0;
}
