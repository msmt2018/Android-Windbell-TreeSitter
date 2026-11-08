#include "ChunkedTextStore.h"
#include "MappedFileSource.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <unistd.h>

using namespace windbell::largefile;

static void testChunkedStoreReadsAcrossBoundaries() {
    ChunkedTextStore store(128u * 1024u, 64u * 1024u);
    std::string first(64u * 1024u, 'a');
    std::string second(17u * 1024u, 'b');
    assert(store.append(first.data(), first.size()));
    // The unused tail of the first allocated chunk remains usable even when
    // the allocation budget is fully committed.
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

static void testChunkedStoreHonorsMemoryBudget() {
    ChunkedTextStore store(64u * 1024u, 64u * 1024u);
    const std::string data(64u * 1024u + 1, 'x');
    assert(!store.append(data.data(), data.size()));
    // The failed append may have filled its first chunk; size remains a valid
    // prefix and the store is intentionally discarded by the caller.
    assert(store.sizeBytes() <= store.allocatedBytes());
}

static void testMappedFileSource() {
    char path[] = "/tmp/windbell-largefile-XXXXXX";
    const int fd = ::mkstemp(path);
    assert(fd >= 0);
    const std::string expected(128u * 1024u + 37u, 'z');
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
    assert(source.read(64u * 1024u + 5u, 1024, &view));
    assert(view.size == 1024);
    assert(std::memcmp(view.data, expected.data() + 64u * 1024u + 5u, 1024) == 0);

    source.close();
    assert(!source.isOpen());
    ::unlink(path);
}

int main() {
    testChunkedStoreReadsAcrossBoundaries();
    testChunkedStoreHonorsMemoryBudget();
    testMappedFileSource();
    return 0;
}
