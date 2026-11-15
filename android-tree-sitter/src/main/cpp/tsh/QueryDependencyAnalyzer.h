#pragma once

#include <cstddef>
#include <string>
#include <string_view>

namespace windbell::tsh {

/**
 * Returns the capture attached directly to a query pattern's outer root
 * expression, but only for the unambiguous S-expression form:
 *
 *   (root_node ...children...) @root_capture
 *
 * Captures nested inside the root, grouped patterns, predicates, alternatives
 * and malformed/unrecognized forms deliberately return an empty string. This
 * is a syntax-level hint only; callers must still validate rooted/non-local
 * metadata and must not infer a dependency from an ordinary descendant capture.
 */
inline std::string findDirectRootCaptureName(std::string_view pattern) {
    size_t position = 0;
    auto skip_space_and_comments = [&]() {
        for (;;) {
            while (position < pattern.size() &&
                   (pattern[position] == ' ' || pattern[position] == '\t' ||
                    pattern[position] == '\r' || pattern[position] == '\n')) {
                ++position;
            }
            if (position >= pattern.size() || pattern[position] != ';') return;
            while (position < pattern.size() && pattern[position] != '\n') ++position;
        }
    };

    skip_space_and_comments();
    if (position >= pattern.size() || pattern[position] != '(') return {};
    size_t depth = 0;
    bool in_string = false;
    bool escaped = false;
    bool root_closed = false;
    for (; position < pattern.size(); ++position) {
        const char ch = pattern[position];
        if (in_string) {
            if (escaped) escaped = false;
            else if (ch == '\\') escaped = true;
            else if (ch == '"') in_string = false;
            continue;
        }
        if (ch == '"') {
            in_string = true;
            continue;
        }
        if (ch == ';') {
            while (position < pattern.size() && pattern[position] != '\n') ++position;
            continue;
        }
        if (ch == '(') {
            ++depth;
        } else if (ch == ')') {
            if (depth == 0) return {};
            --depth;
            if (depth == 0) {
                ++position;
                root_closed = true;
                break;
            }
        }
    }
    if (!root_closed || in_string) return {};

    skip_space_and_comments();
    if (position >= pattern.size() || pattern[position] != '@') return {};
    ++position;
    const size_t name_start = position;
    while (position < pattern.size()) {
        const unsigned char ch = static_cast<unsigned char>(pattern[position]);
        if (ch <= 0x20 || ch == '(' || ch == ')' || ch == '[' || ch == ']' ||
            ch == '{' || ch == '}' || ch == ';') break;
        ++position;
    }
    if (position == name_start) return {};
    return std::string(pattern.substr(name_start, position - name_start));
}

} // namespace windbell::tsh
