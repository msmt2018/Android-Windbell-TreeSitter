#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "oniguruma.h"

namespace windbell::tsh {

struct TshRegexNativeMatch {
    int32_t start_utf16 = 0;
    int32_t end_utf16 = 0;
    std::vector<std::pair<int32_t, int32_t>> capture_ranges_utf16;
};

struct TshRegexNativeBatch {
    std::vector<TshRegexNativeMatch> matches;
    bool truncated = false;
};

/**
 * Owns compiled Oniguruma regexes independently from grammar registrations.
 * Handles are opaque monotonically allocated IDs; no raw native pointer crosses JNI.
 */
class TshRegexNativeRegistry final {
public:
    static TshRegexNativeRegistry& instance();

    uint64_t compile(const UChar* pattern, size_t pattern_bytes, std::string& error);
    bool release(uint64_t handle);
    bool execute(
        uint64_t handle,
        const UChar* text,
        size_t text_bytes,
        int32_t core_start_utf16,
        int32_t core_end_utf16,
        bool document_start,
        bool document_end,
        uint32_t max_matches,
        uint32_t max_capture_groups,
        TshRegexNativeBatch& output,
        std::string& error
    );

private:
    struct Entry {
        explicit Entry(regex_t* value) : regex(value) {}
        ~Entry() { if (regex) onig_free(regex); }
        regex_t* regex = nullptr;
        std::mutex execution_mutex;
    };

    std::mutex mutex_;
    std::unordered_map<uint64_t, std::shared_ptr<Entry>> entries_;
    uint64_t next_handle_ = 1;
};

} // namespace windbell::tsh
