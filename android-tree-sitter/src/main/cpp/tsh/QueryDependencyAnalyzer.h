#pragma once

#include <cstddef>
#include <string>
#include <string_view>

namespace windbell::tsh {

struct QueryRootDescriptor {
    std::string node_type;
    std::string direct_capture;
    bool is_named = false;
    bool valid = false;
};

inline void skipQuerySpaceAndComments(std::string_view source, size_t *position) {
    if (!position) return;
    for (;;) {
        while (*position < source.size() &&
               (source[*position] == ' ' || source[*position] == '\t' ||
                source[*position] == '\r' || source[*position] == '\n')) {
            ++*position;
        }
        if (*position >= source.size() || source[*position] != ';') return;
        while (*position < source.size() && source[*position] != '\n') ++*position;
    }
}

/**
 * Extracts only a simple, fixed root node type from a single S-expression
 * pattern. Wildcards, supertypes, alternatives, grouped patterns and unknown
 * syntax return valid=false so callers can conservatively use document scope.
 */
inline QueryRootDescriptor analyzeSimpleQueryRoot(std::string_view pattern) {
    QueryRootDescriptor result;
    size_t position = 0;
    skipQuerySpaceAndComments(pattern, &position);
    if (position >= pattern.size() || pattern[position++] != '(') return result;
    skipQuerySpaceAndComments(pattern, &position);
    if (position >= pattern.size()) return result;

    if (pattern[position] == '"') {
        ++position;
        std::string node_type;
        bool closed = false;
        while (position < pattern.size()) {
            const char ch = pattern[position++];
            if (ch == '"') { closed = true; break; }
            if (ch == '\\') {
                if (position >= pattern.size()) return result;
                const char escaped = pattern[position++];
                if (escaped != '"' && escaped != '\\') return result;
                node_type.push_back(escaped);
            } else {
                node_type.push_back(ch);
            }
        }
        if (!closed || node_type.empty()) return result;
        result.node_type = std::move(node_type);
        result.is_named = false;
    } else {
        const size_t begin = position;
        while (position < pattern.size()) {
            const unsigned char ch = static_cast<unsigned char>(pattern[position]);
            if (ch <= 0x20 || ch == ')' || ch == '(' || ch == ':' ||
                ch == '[' || ch == ']' || ch == '/' || ch == '.') break;
            ++position;
        }
        if (position == begin) return result;
        const std::string_view node_type = pattern.substr(begin, position - begin);
        if (node_type == "_" || node_type.find('*') != std::string_view::npos) return result;
        result.node_type.assign(node_type);
        result.is_named = true;
    }
    result.valid = true;

    // The root node's closing delimiter is the first close that returns the
    // initial S-expression depth to zero. Ignore nested strings/comments.
    size_t scan = position;
    size_t depth = 1;
    bool in_string = false;
    bool escaped = false;
    bool closed_root = false;
    for (; scan < pattern.size(); ++scan) {
        const char ch = pattern[scan];
        if (in_string) {
            if (escaped) escaped = false;
            else if (ch == '\\') escaped = true;
            else if (ch == '"') in_string = false;
            continue;
        }
        if (ch == '"') { in_string = true; continue; }
        if (ch == ';') {
            while (scan < pattern.size() && pattern[scan] != '\n') ++scan;
            continue;
        }
        if (ch == '(') ++depth;
        else if (ch == ')') {
            if (depth == 0) return result;
            if (--depth == 0) { ++scan; closed_root = true; break; }
        }
    }
    if (!closed_root || in_string) return result;
    skipQuerySpaceAndComments(pattern, &scan);
    if (scan < pattern.size() && pattern[scan] == '@') {
        ++scan;
        const size_t begin = scan;
        while (scan < pattern.size()) {
            const unsigned char ch = static_cast<unsigned char>(pattern[scan]);
            if (ch <= 0x20 || ch == '(' || ch == ')' || ch == '[' || ch == ']' ||
                ch == '{' || ch == '}' || ch == ';') break;
            ++scan;
        }
        if (scan > begin) result.direct_capture.assign(pattern.substr(begin, scan - begin));
    }
    return result;
}

inline std::string findDirectRootCaptureName(std::string_view pattern) {
    return analyzeSimpleQueryRoot(pattern).direct_capture;
}

} // namespace windbell::tsh
