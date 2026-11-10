#include "TshRegexNative.h"

#include <algorithm>
#include <limits>
#include <new>

namespace windbell::tsh {

TshRegexNativeRegistry& TshRegexNativeRegistry::instance() {
    static TshRegexNativeRegistry registry;
    return registry;
}

uint64_t TshRegexNativeRegistry::compile(
    const UChar* pattern,
    size_t pattern_bytes,
    std::string& error
) {
    constexpr size_t kMaxPatternUtf16 = 1024u * 1024u;
    if ((!pattern && pattern_bytes != 0) || (pattern_bytes & 1u) != 0 ||
        pattern_bytes / 2u > kMaxPatternUtf16 ||
        pattern_bytes > static_cast<size_t>(std::numeric_limits<ptrdiff_t>::max())) {
        error = "Regex pattern has an invalid or oversized UTF-16LE buffer";
        return 0;
    }
    const uint16_t* pattern_units = reinterpret_cast<const uint16_t*>(pattern);
    const size_t pattern_length = pattern_bytes / 2u;
    for (size_t i = 0; i < pattern_length; ++i) {
        const uint16_t unit = pattern_units[i];
        if (unit >= 0xD800 && unit <= 0xDBFF) {
            if (i + 1 >= pattern_length ||
                pattern_units[i + 1] < 0xDC00 || pattern_units[i + 1] > 0xDFFF) {
                error = "Regex pattern contains an unpaired UTF-16 high surrogate";
                return 0;
            }
            ++i;
        } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
            error = "Regex pattern contains an unpaired UTF-16 low surrogate";
            return 0;
        }
    }

    regex_t* compiled = nullptr;
    OnigErrorInfo info{};
    static const UChar empty_pattern[] = {0, 0};
    const UChar* begin = pattern_bytes == 0 ? empty_pattern : pattern;
    const UChar* end = begin + pattern_bytes;
    const int status = onig_new(
        &compiled, begin, end, ONIG_OPTION_DEFAULT,
        ONIG_ENCODING_UTF16_LE, ONIG_SYNTAX_DEFAULT, &info
    );
    if (status != ONIG_NORMAL || !compiled) {
        if (compiled) {
            onig_free(compiled);
            compiled = nullptr;
        }
        UChar message[ONIG_MAX_ERROR_MESSAGE_LEN]{};
        onig_error_code_to_str(message, status, &info);
        error.assign(reinterpret_cast<const char*>(message));
        if (error.empty()) error = "Oniguruma rejected the Regex pattern";
        return 0;
    }

    std::shared_ptr<Entry> entry;
    try {
        entry = std::make_shared<Entry>(compiled);
    } catch (...) {
        onig_free(compiled);
        throw;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (next_handle_ == 0 || next_handle_ == std::numeric_limits<uint64_t>::max()) {
        error = "Regex native handle space is exhausted";
        return 0;
    }
    const uint64_t handle = next_handle_++;
    entries_.emplace(handle, std::move(entry));
    return handle;
}

bool TshRegexNativeRegistry::release(uint64_t handle) {
    std::shared_ptr<Entry> removed;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = entries_.find(handle);
        if (found == entries_.end()) return false;
        removed = std::move(found->second);
        entries_.erase(found);
    }
    // An in-flight execute retains its own shared reference until it returns.
    return true;
}

bool TshRegexNativeRegistry::execute(
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
) {
    output = {};
    if ((!text && text_bytes != 0) || (text_bytes & 1u) != 0 ||
        text_bytes / 2u > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        error = "Regex window is not a valid bounded UTF-16LE buffer";
        return false;
    }
    static const UChar empty_text[] = {0, 0};
    if (!text) text = empty_text;
    const int32_t length = static_cast<int32_t>(text_bytes / 2u);
    if (core_start_utf16 < 0 || core_end_utf16 < core_start_utf16 ||
        core_end_utf16 > length || max_matches == 0 || max_capture_groups == 0) {
        error = "Regex core range or execution budget is invalid";
        return false;
    }

    std::shared_ptr<Entry> entry;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = entries_.find(handle);
        if (found == entries_.end()) {
            error = "Regex handle is closed or unknown";
            return false;
        }
        entry = found->second;
    }

    std::lock_guard<std::mutex> execution_lock(entry->execution_mutex);
    OnigRegion* raw_region = onig_region_new();
    if (!raw_region) {
        error = "Cannot allocate Oniguruma match region";
        return false;
    }
    struct RegionGuard {
        OnigRegion* value;
        ~RegionGuard() { if (value) onig_region_free(value, 1); }
    } region{raw_region};

    const UChar* begin = text;
    const UChar* end = text + text_bytes;
    const UChar* current = begin;
    const UChar* search_range = end;
    OnigOptionType options = ONIG_OPTION_NONE;
    if (!document_start) options |= ONIG_OPTION_NOTBOL;
    if (!document_end) options |= ONIG_OPTION_NOTEOL;

    while (current <= end) {
        const OnigPosition hit = onig_search(
            entry->regex, begin, end, current, search_range, region.value, options
        );
        if (hit < 0) {
            if (hit != ONIG_MISMATCH) {
                error = "Oniguruma Regex execution failed with status " + std::to_string(hit);
                return false;
            }
            break;
        }

        const OnigPosition start_byte_position = region.value->beg[0];
        const OnigPosition end_byte_position = region.value->end[0];
        if (start_byte_position < 0 || end_byte_position < start_byte_position ||
            end_byte_position > static_cast<OnigPosition>(text_bytes) ||
            (start_byte_position & 1) != 0 || (end_byte_position & 1) != 0) {
            error = "Oniguruma returned a malformed UTF-16 match range";
            return false;
        }
        const int32_t start_byte = static_cast<int32_t>(start_byte_position);
        const int32_t end_byte = static_cast<int32_t>(end_byte_position);
        const int32_t start = start_byte / 2;
        const int32_t finish = end_byte / 2;

        // Core ownership is half-open. Only the final document EOF owns its zero-width match.
        if (start > core_end_utf16 ||
            (start == core_end_utf16 &&
             !(document_end && core_end_utf16 == length && finish == start))) {
            break;
        }
        if (start >= core_start_utf16) {
            if (output.matches.size() >= max_matches) {
                output.truncated = true;
                break;
            }
            TshRegexNativeMatch match;
            match.start_utf16 = start;
            match.end_utf16 = finish;
            const int capture_count = std::max(0, region.value->num_regs);
            const int retained_count = std::min<int>(
                capture_count, static_cast<int>(max_capture_groups) + 1
            );
            if (capture_count > retained_count) output.truncated = true;
            match.capture_ranges_utf16.reserve(static_cast<size_t>(retained_count));
            for (int group = 0; group < retained_count; ++group) {
                const int beg = region.value->beg[group];
                const int group_end = region.value->end[group];
                if (beg < 0 || group_end < 0) {
                    match.capture_ranges_utf16.emplace_back(-1, -1);
                } else {
                    if ((beg & 1) != 0 || (group_end & 1) != 0 ||
                        group_end < beg || group_end > static_cast<int32_t>(text_bytes)) {
                        error = "Oniguruma returned a malformed UTF-16 capture range";
                        return false;
                    }
                    match.capture_ranges_utf16.emplace_back(beg / 2, group_end / 2);
                }
            }
            output.matches.emplace_back(std::move(match));
        }

        if (end_byte > start_byte) {
            current = begin + end_byte;
        } else {
            if (end_byte >= static_cast<int32_t>(text_bytes)) break;
            const int32_t next_unit = end_byte / 2;
            const uint16_t* units = reinterpret_cast<const uint16_t*>(text);
            const bool starts_surrogate_pair =
                next_unit < length &&
                units[next_unit] >= 0xD800 && units[next_unit] <= 0xDBFF &&
                next_unit + 1 < length &&
                units[next_unit + 1] >= 0xDC00 && units[next_unit + 1] <= 0xDFFF;
            current = begin + end_byte + (starts_surrogate_pair ? 2 * sizeof(uint16_t) : sizeof(uint16_t));
        }
    }
    return true;
}

} // namespace windbell::tsh
