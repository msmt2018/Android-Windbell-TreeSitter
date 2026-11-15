#pragma once

#include <cstddef>
#include <string>
#include <string_view>

namespace windbell::tsh {

struct QueryRootDescriptor {
    std::string node_type;
    bool is_named = false;
    bool valid = false;
};

inline void skipQuerySpaceAndComments(std::string_view source, size_t *position) {
    if (!position) return;
    for (;;) {
        while (*position < source.size() &&
               (source[*position] == ' ' || source[*position] == '\t' ||
                source[*position] == '\r' || source[*position] == '\n')) ++*position;
        if (*position >= source.size() || source[*position] != ';') return;
        while (*position < source.size() && source[*position] != '\n') ++*position;
    }
}

/**
 * Extract a fixed root node type from a simple, single-root S-expression.
 * Wildcards, supertypes, alternatives, grouped roots, anchors outside the
 * root and unrecognized syntax remain UNKNOWN and must use document scope.
 */
inline bool queryPredicateRequiresAncestorContext(std::string_view operation) {
    if (!operation.empty() && operation.front() == '#') operation.remove_prefix(1);
    return operation == "has-parent?" || operation == "not-has-parent?" ||
        operation == "any-has-parent?" || operation == "any-not-has-parent?" ||
        operation == "has-ancestor?" || operation == "not-has-ancestor?" ||
        operation == "any-has-ancestor?" || operation == "any-not-has-ancestor?";
}

inline QueryRootDescriptor analyzeSimpleQueryRoot(std::string_view pattern) {
    QueryRootDescriptor result;
    size_t position = 0;
    skipQuerySpaceAndComments(pattern, &position);
    if (position >= pattern.size() || pattern[position++] != '(') return result;
    skipQuerySpaceAndComments(pattern, &position);
    if (position >= pattern.size()) return result;

    if (pattern[position] == '"') {
        ++position;
        bool closed = false;
        while (position < pattern.size()) {
            const char ch = pattern[position++];
            if (ch == '"') { closed = true; break; }
            if (ch == '\\') {
                if (position >= pattern.size()) return result;
                const char escaped = pattern[position++];
                if (escaped != '"' && escaped != '\\') return result;
                result.node_type.push_back(escaped);
            } else result.node_type.push_back(ch);
        }
        if (!closed || result.node_type.empty()) return result;
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
        const std::string_view type = pattern.substr(begin, position - begin);
        if (type == "_" || type.find('*') != std::string_view::npos ||
            (position < pattern.size() && pattern[position] == '/')) return result;
        result.node_type.assign(type);
        result.is_named = true;
    }

    // Find the close of the outer root expression, ignoring nested strings,
    // comments and child-node expressions.
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
            if (depth == 0) return QueryRootDescriptor{};
            if (--depth == 0) { ++scan; closed_root = true; break; }
        }
    }
    if (!closed_root || in_string) return QueryRootDescriptor{};

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
        if (scan == begin) return QueryRootDescriptor{};
        // The root capture is syntactically consumed here to validate the
        // suffix, but capture IDs are not used as dependency-range proxies.
    }

    skipQuerySpaceAndComments(pattern, &scan);
    if (scan < pattern.size()) {
        // Only standard predicate groups are accepted after the root. Other
        // suffix syntax (notably sibling anchors) expands dependencies.
        if (pattern[scan] != '(') return QueryRootDescriptor{};
        size_t predicate = scan + 1;
        skipQuerySpaceAndComments(pattern, &predicate);
        if (predicate >= pattern.size() || pattern[predicate] != '#')
            return QueryRootDescriptor{};
    }
    result.valid = true;
    return result;
}


} // namespace windbell::tsh
