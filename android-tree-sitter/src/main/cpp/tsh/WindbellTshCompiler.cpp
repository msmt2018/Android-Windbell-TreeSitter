/*
 * Copyright © 2026 com.zerostudio.idea.treesitter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
 * 纯 C++20 词法分析与递归下降 DSL 编译器（完全解耦、零硬编码、多主题方案驱动）
 */

#include "WindbellTshCompiler.h"
#include <cctype>
#include <sstream>
#include <algorithm>
#include <vector>
#include <functional>
#include <cmath>
#include <limits>
#include "ts__log.h"

namespace windbell::tsh {

static thread_local uint32_t g_tsh_last_source_offset = 0;
static thread_local std::string g_tsh_last_error_detail;

// 内部高性能 DSL 词法分析器（支持 JSON5、嵌套块、注释过滤、正则解析）
class TshLexer {
public:
    enum class TokenType {
        TOK_EOF,
        TOK_INVALID,
        TOK_IDENT,
        TOK_NUMBER,
        TOK_STRING,
        TOK_REGEX,
        TOK_LBRACE,   // {
        TOK_RBRACE,   // }
        TOK_LBRACKET, // [
        TOK_RBRACKET, // ]
        TOK_COLON,    // :
        TOK_COMMA,    // ,
        TOK_GT,       // >
        TOK_AT        // @
    };

    struct Token {
        TokenType type;
        std::string text;
    };

    explicit TshLexer(const std::string& src) : src_(src), cursor_(0) {}

    void putBack(Token token) {
        pending_ = std::move(token);
        has_pending_ = true;
    }

    Token nextToken() {
        if (has_pending_) {
            has_pending_ = false;
            return std::move(pending_);
        }
        skipIgnored();
        g_tsh_last_source_offset = static_cast<uint32_t>(cursor_);
        auto invalid = [&](const std::string& reason) {
            g_tsh_last_error_detail = reason;
            return Token{TokenType::TOK_INVALID, reason};
        };
        if (lexical_error_) {
            g_tsh_last_error_detail = "unterminated block comment";
            return {TokenType::TOK_INVALID, g_tsh_last_error_detail};
        }
        if (cursor_ >= src_.size()) {
            return {TokenType::TOK_EOF, ""};
        }

        char c = src_[cursor_];
        if (c == '{') { cursor_++; return {TokenType::TOK_LBRACE, "{"}; }
        if (c == '}') { cursor_++; return {TokenType::TOK_RBRACE, "}"}; }
        if (c == '[') { cursor_++; return {TokenType::TOK_LBRACKET, "["}; }
        if (c == ']') { cursor_++; return {TokenType::TOK_RBRACKET, "]"}; }
        if (c == ':') { cursor_++; return {TokenType::TOK_COLON, ":"}; }
        if (c == ',') { cursor_++; return {TokenType::TOK_COMMA, ","}; }
        if (c == '>') { cursor_++; return {TokenType::TOK_GT, ">"}; }
        if (c == '@') { cursor_++; return {TokenType::TOK_AT, "@"}; }

        if (std::isdigit(static_cast<unsigned char>(c)) ||
            ((c == '-' || c == '+') && cursor_ + 1 < src_.size() &&
             std::isdigit(static_cast<unsigned char>(src_[cursor_ + 1])))) {
            std::string number;
            if (src_[cursor_] == '-' || src_[cursor_] == '+') number += src_[cursor_++];
            while (cursor_ < src_.size() && std::isdigit(static_cast<unsigned char>(src_[cursor_]))) {
                number += src_[cursor_++];
            }
            if (cursor_ + 1 < src_.size() && src_[cursor_] == '.' &&
                std::isdigit(static_cast<unsigned char>(src_[cursor_ + 1]))) {
                number += src_[cursor_++];
                while (cursor_ < src_.size() && std::isdigit(static_cast<unsigned char>(src_[cursor_]))) {
                    number += src_[cursor_++];
                }
            }
            if (cursor_ < src_.size() && (src_[cursor_] == 'e' || src_[cursor_] == 'E')) {
                const size_t exponent_start = cursor_;
                std::string exponent;
                exponent += src_[cursor_++];
                if (cursor_ < src_.size() && (src_[cursor_] == '-' || src_[cursor_] == '+')) {
                    exponent += src_[cursor_++];
                }
                const size_t digits_start = cursor_;
                while (cursor_ < src_.size() && std::isdigit(static_cast<unsigned char>(src_[cursor_]))) {
                    exponent += src_[cursor_++];
                }
                if (cursor_ == digits_start) cursor_ = exponent_start;
                else number += exponent;
            }
            return {TokenType::TOK_NUMBER, number};
        }

        // Quoted strings use JSON-style escapes; backticks are raw multiline strings.
        if (c == '"' || c == '\'' || c == '`') {
            const char quote = c;
            const bool raw_string = quote == '`';
            ++cursor_;
            std::string content;
            auto append_utf8 = [&](uint32_t cp) {
                if (cp == 0) {
                    // JNI modified UTF-8 encodes U+0000 as C0 80, never a C NUL.
                    content.push_back(static_cast<char>(0xC0));
                    content.push_back(static_cast<char>(0x80));
                } else if (cp <= 0x7F) content.push_back(static_cast<char>(cp));
                else if (cp <= 0x7FF) {
                    content.push_back(static_cast<char>(0xC0 | (cp >> 6)));
                    content.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
                } else if (cp <= 0xFFFF) {
                    content.push_back(static_cast<char>(0xE0 | (cp >> 12)));
                    content.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                    content.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
                } else {
                    // JNI uses modified UTF-8: non-BMP scalars are encoded
                    // as their two UTF-16 surrogate code units.
                    const uint32_t scalar = cp - 0x10000;
                    const uint32_t high = 0xD800 + (scalar >> 10);
                    const uint32_t low = 0xDC00 + (scalar & 0x3FF);
                    for (uint32_t unit : {high, low}) {
                        content.push_back(static_cast<char>(0xE0 | (unit >> 12)));
                        content.push_back(static_cast<char>(0x80 | ((unit >> 6) & 0x3F)));
                        content.push_back(static_cast<char>(0x80 | (unit & 0x3F)));
                    }
                }
            };
            auto read_hex4 = [&](uint32_t& value) -> bool {
                if (cursor_ + 4 > src_.size()) return false;
                value = 0;
                for (int i = 0; i < 4; ++i) {
                    const unsigned char digit = static_cast<unsigned char>(src_[cursor_++]);
                    value <<= 4;
                    if (digit >= '0' && digit <= '9') value |= digit - '0';
                    else if (digit >= 'a' && digit <= 'f') value |= digit - 'a' + 10;
                    else if (digit >= 'A' && digit <= 'F') value |= digit - 'A' + 10;
                    else return false;
                }
                return true;
            };
            while (cursor_ < src_.size() && src_[cursor_] != quote) {
                const char current = src_[cursor_++];
                if (!raw_string && (current == '\n' || current == '\r')) {
                    return invalid("unescaped newline in quoted string");
                }
                if (raw_string || current != '\\') {
                    content.push_back(current);
                    continue;
                }
                if (cursor_ >= src_.size()) return invalid("unterminated escape");
                const char escaped = src_[cursor_++];
                switch (escaped) {
                    case '"': content.push_back('"'); break;
                    case '\'': content.push_back('\''); break;
                    case '\\': content.push_back('\\'); break;
                    case '/': content.push_back('/'); break;
                    case 'b': content.push_back('\b'); break;
                    case 'f': content.push_back('\f'); break;
                    case 'n': content.push_back('\n'); break;
                    case 'r': content.push_back('\r'); break;
                    case 't': content.push_back('\t'); break;
                    case 'u': {
                        uint32_t cp = 0;
                        if (!read_hex4(cp)) return invalid("invalid unicode escape");
                        if (cp >= 0xD800 && cp <= 0xDBFF) {
                            if (cursor_ + 6 > src_.size() || src_[cursor_] != '\\' ||
                                src_[cursor_ + 1] != 'u') {
                                return invalid("unpaired high surrogate");
                            }
                            cursor_ += 2;
                            uint32_t low = 0;
                            if (!read_hex4(low) || low < 0xDC00 || low > 0xDFFF) {
                                return invalid("invalid low surrogate");
                            }
                            cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                            return invalid("unpaired low surrogate");
                        }
                        append_utf8(cp);
                        break;
                    }
                    default: return invalid("unsupported string escape");
                }
            }
            if (cursor_ >= src_.size()) return invalid("unterminated string");
            ++cursor_;
            return {TokenType::TOK_STRING, content};
        }

        // 解析正则表达式 /.../
        if (c == '/') {
            cursor_++;
            std::string regex_body;
            while (cursor_ < src_.size() && src_[cursor_] != '/') {
                if (src_[cursor_] == '\\' && cursor_ + 1 < src_.size()) {
                    regex_body += src_[cursor_++];
                }
                regex_body += src_[cursor_++];
            }
            if (cursor_ >= src_.size()) return invalid("unterminated regex");
            cursor_++;
            return {TokenType::TOK_REGEX, regex_body};
        }

        // 解析标识符、宏定义、颜色（如 keywordsToRegex, #FF0000, @B）
        if (std::isalpha(static_cast<unsigned char>(c)) || c == '_' || c == '#' || c == '.') {
            std::string ident;
            while (cursor_ < src_.size()) {
                char cur = src_[cursor_];
                if (std::isalnum(static_cast<unsigned char>(cur)) || cur == '_' || cur == '#' || cur == '.' || cur == '-') {
                    ident += cur;
                    cursor_++;
                } else {
                    break;
                }
            }
            return {TokenType::TOK_IDENT, ident};
        }

        ++cursor_;
        g_tsh_last_error_detail = std::string("unexpected character: ") + c;
        return {TokenType::TOK_INVALID, g_tsh_last_error_detail};
    }

    // 提取括号内的原始内容（用于 keywordsToRegex("...") 提取）
    std::string extractParenthesizedBlock() {
        skipIgnored();
        if (cursor_ < src_.size() && src_[cursor_] == '(') {
            cursor_++;
            std::string block;
            int depth = 1;
            while (cursor_ < src_.size() && depth > 0) {
                if (src_[cursor_] == '(') depth++;
                else if (src_[cursor_] == ')') depth--;
                if (depth > 0) block += src_[cursor_];
                cursor_++;
            }
            if (depth != 0) {
                lexical_error_ = true;
                g_tsh_last_source_offset = static_cast<uint32_t>(cursor_);
                return "";
            }
            return block;
        }
        return "";
    }

private:
    void skipIgnored() {
        while (cursor_ < src_.size()) {
            char c = src_[cursor_];
            if (std::isspace(static_cast<unsigned char>(c))) {
                cursor_++;
            } else if (c == '/' && cursor_ + 1 < src_.size() && src_[cursor_ + 1] == '/') {
                cursor_ += 2;
                while (cursor_ < src_.size() && src_[cursor_] != '\n') cursor_++;
            } else if (c == '/' && cursor_ + 1 < src_.size() && src_[cursor_ + 1] == '*') {
                cursor_ += 2;
                while (cursor_ + 1 < src_.size() && !(src_[cursor_] == '*' && src_[cursor_ + 1] == '/')) cursor_++;
                if (cursor_ + 1 >= src_.size()) {
                    cursor_ = src_.size();
                    lexical_error_ = true;
                    return;
                }
                cursor_ += 2;
            } else {
                break;
            }
        }
    }

    const std::string& src_;
    size_t cursor_;
    Token pending_{TokenType::TOK_EOF, ""};
    bool has_pending_ = false;
    bool lexical_error_ = false;
};

std::shared_ptr<TshGrammar> TshCompiler::compile(const std::string& source, const TSLanguage* ts_lang) {
    auto compiled = compileDetailed(source, ts_lang);
    {
        std::lock_guard<std::mutex> lock(diagnostic_mutex_);
        last_diagnostics_ = compiled.diagnostics;
    }
    return std::move(compiled.grammar);
}

TshCompileResult TshCompiler::compileDetailed(const std::string& source, const TSLanguage* ts_lang) {
    g_tsh_last_source_offset = 0;
    g_tsh_last_error_detail.clear();
    auto result = compileInternal(source, ts_lang);
    std::vector<TshCompileDiagnostic> diagnostics;
    if (!result) {
        const uint32_t offset = std::min<uint32_t>(
            g_tsh_last_source_offset, static_cast<uint32_t>(source.size()));
        uint32_t line = 1;
        uint32_t column = 1;
        for (uint32_t i = 0; i < offset; ++i) {
            const uint8_t byte = static_cast<uint8_t>(source[i]);
            if (byte == '\n') {
                ++line;
                column = 1;
            } else if (byte == '\r') {
                // CR is part of CRLF and does not create a second visual column.
            } else if ((byte & 0xC0) != 0x80) {
                // Count UTF-8 leading bytes, not continuation bytes. This matches
                // UTF-16 editor columns for modified-UTF-8 surrogate code units.
                ++column;
            }
        }
        diagnostics.push_back({
            "TSH_PARSE_OR_COMPILE_ERROR",
            g_tsh_last_error_detail.empty()
                ? "TSH compilation failed near this location. Check the current property value, delimiters, references, regex/query syntax and required fields."
                : std::string("TSH compilation failed: ") + g_tsh_last_error_detail,
            offset, line, column
        });
    }
    return TshCompileResult{std::move(result), std::move(diagnostics)};
}

std::vector<TshCompileDiagnostic> TshCompiler::getLastDiagnostics() const {
    std::lock_guard<std::mutex> lock(diagnostic_mutex_);
    return last_diagnostics_;
}

std::string TshCompiler::expandKeywordsToRegex(const std::string& word_list) {
    std::istringstream iss(word_list);
    std::string item;
    std::vector<std::string> words;
    while (iss >> item) {
        if (!item.empty() && (item.front() == '"' || item.front() == '\'')) item = item.substr(1);
        if (!item.empty() && (item.back() == '"' || item.back() == '\'')) item.pop_back();
        if (!item.empty()) words.push_back(item);
    }
    if (words.empty()) return "";

    std::string out = "\\b(?:";
    for (size_t i = 0; i < words.size(); ++i) {
        if (i > 0) out += "|";
        out += words[i];
    }
    out += ")\\b";
    return out;
}

static bool isValidColorHex(const std::string& value) {
    std::string clean = value;
    if (!clean.empty() && clean.front() == '#') clean.erase(clean.begin());
    if (clean.size() != 3 && clean.size() != 4 &&
        clean.size() != 6 && clean.size() != 8) return false;
    return std::all_of(clean.begin(), clean.end(), [](unsigned char ch) {
        return std::isxdigit(ch) != 0;
    });
}

static bool expandRepositoryIncludes(
    const std::string& input,
    const std::unordered_map<std::string, std::string>& repository,
    std::unordered_map<std::string, bool>& active,
    std::string& output
) {
    size_t cursor = 0;
    while (cursor < input.size()) {
        const size_t include_pos = input.find("include(", cursor);
        if (include_pos == std::string::npos) {
            output.append(input, cursor, std::string::npos);
            return true;
        }
        output.append(input, cursor, include_pos - cursor);
        size_t name_start = include_pos + 8;
        while (name_start < input.size() && std::isspace(static_cast<unsigned char>(input[name_start]))) ++name_start;
        if (name_start >= input.size() || (input[name_start] != '"' && input[name_start] != '\'')) return false;
        const char quote = input[name_start++];
        const size_t name_end = input.find(quote, name_start);
        if (name_end == std::string::npos) return false;
        std::string name = input.substr(name_start, name_end - name_start);
        size_t close = name_end + 1;
        while (close < input.size() && std::isspace(static_cast<unsigned char>(input[close]))) ++close;
        if (close >= input.size() || input[close] != ')') return false;
        if (!name.empty() && name.front() == '#') name.erase(name.begin());
        auto entry = repository.find(name);
        if (entry == repository.end() || active[name]) return false;
        active[name] = true;
        if (!expandRepositoryIncludes(entry->second, repository, active, output)) return false;
        active.erase(name);
        cursor = close + 1;
    }
    return true;
}

uint32_t TshCompiler::parseColorHex(const std::string& hex_str) {
    std::string clean = hex_str;
    if (!clean.empty() && clean.front() == '#') clean.erase(clean.begin());

    // Normalize CSS-style RGB/RGBA and Android-style ARGB into AARRGGBB.
    if (clean.size() == 3 || clean.size() == 4) {
        std::string expanded;
        expanded.reserve(clean.size() * 2);
        for (char ch : clean) {
            expanded.push_back(ch);
            expanded.push_back(ch);
        }
        if (expanded.size() == 6) {
            clean = "FF" + expanded;
        } else {
            // CSS #RGBA becomes canonical AARRGGBB.
            clean = expanded.substr(6, 2) + expanded.substr(0, 6);
        }
    } else if (clean.size() == 6) {
        clean.insert(0, "FF");
    } else if (clean.size() != 8) {
        return 0;
    }

    uint32_t value = 0;
    std::stringstream stream;
    stream << std::hex << clean;
    stream >> value;
    if (stream.fail() || !stream.eof()) return 0;
    return value;
}

static void releaseCompiledRegexRule(CompiledRegexRule& rule) {
    for (auto& nested : rule.nested_rules) releaseCompiledRegexRule(nested);
    if (rule.onig_start) { onig_free(rule.onig_start); rule.onig_start = nullptr; }
    if (rule.onig_end) { onig_free(rule.onig_end); rule.onig_end = nullptr; }
}

class CompiledRegexRuleGuard {
public:
    explicit CompiledRegexRuleGuard(CompiledRegexRule& rule) : rule_(rule) {}
    ~CompiledRegexRuleGuard() { if (active_) releaseCompiledRegexRule(rule_); }
    void release() { active_ = false; }
private:
    CompiledRegexRule& rule_;
    bool active_ = true;
};

class MonarchRuleGuard {
public:
    explicit MonarchRuleGuard(MonarchRule& rule) : rule_(rule) {}
    ~MonarchRuleGuard() {
        if (active_) {
            if (rule_.pattern) { onig_free(rule_.pattern); rule_.pattern = nullptr; }
            for (auto& entry : rule_.cases) {
                if (entry.matcher) { onig_free(entry.matcher); entry.matcher = nullptr; }
            }
        }
    }
    void release() { active_ = false; }
private:
    MonarchRule& rule_;
    bool active_ = true;
};

static std::string trimScopeToken(const std::string& value) {
    const size_t first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return "";
    const size_t last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

static bool scopeAtomMatches(const std::string& selector_atom, const std::string& scope) {
    if (selector_atom == "*") return true;
    if (selector_atom.size() > 2 &&
        selector_atom.compare(selector_atom.size() - 2, 2, ".*") == 0) {
        const std::string prefix = selector_atom.substr(0, selector_atom.size() - 1);
        return scope.rfind(prefix, 0) == 0;
    }
    return scope == selector_atom ||
        (scope.size() > selector_atom.size() &&
         scope.compare(0, selector_atom.size(), selector_atom) == 0 &&
         scope[selector_atom.size()] == '.');
}

static int matchOneTextMateSelector(const std::string& selector, const std::vector<std::string>& scopes) {
    std::vector<std::string> tokens;
    std::string current;
    auto flush = [&]() {
        if (!current.empty()) {
            tokens.push_back(current);
            current.clear();
        }
    };
    for (char ch : selector) {
        if (std::isspace(static_cast<unsigned char>(ch))) {
            flush();
        } else if (ch == '>') {
            flush();
            tokens.emplace_back(">");
        } else {
            current.push_back(ch);
        }
    }
    flush();

    std::vector<std::pair<std::string, bool>> positive;
    std::vector<std::string> excluded;
    bool exclusion_mode = false;
    bool direct_child = false;
    for (const auto& token : tokens) {
        if (token == ">") { direct_child = true; continue; }
        if (token == "-") { exclusion_mode = true; direct_child = false; continue; }
        if (exclusion_mode) excluded.push_back(token);
        else {
            positive.emplace_back(token, direct_child);
            direct_child = false;
        }
    }
    if (positive.empty()) return -1;

    int previous_index = -1;
    int specificity = 0;
    for (size_t term = 0; term < positive.size(); ++term) {
        const auto& atom = positive[term].first;
        const bool direct = positive[term].second;
        int found = -1;
        const int start = term == 0 ? 0 : previous_index + 1;
        const int end = direct && term > 0 ?
            std::min<int>(static_cast<int>(scopes.size()), previous_index + 2) :
            static_cast<int>(scopes.size());
        for (int index = start; index < end; ++index) {
            if (scopeAtomMatches(atom, scopes[static_cast<size_t>(index)])) {
                found = index;
                break;
            }
        }
        if (found < 0) return -1;
        previous_index = found;
        specificity += 100 + static_cast<int>(std::min<size_t>(atom.size(), 32));
        if (direct) specificity += 20;
    }
    for (const auto& atom : excluded) {
        for (const auto& scope : scopes) {
            if (scopeAtomMatches(atom, scope)) return -1;
        }
    }
    return specificity;
}

static void splitSelectorAlternatives(
    const std::string& input,
    std::vector<std::string>& output
) {
    size_t start = 0;
    int depth = 0;
    for (size_t i = 0; i <= input.size(); ++i) {
        const char ch = i < input.size() ? input[i] : ',';
        if (ch == '(') ++depth;
        else if (ch == ')' && depth > 0) --depth;
        else if ((ch == ',' || ch == '|') && depth == 0) {
            const std::string part = trimScopeToken(input.substr(start, i - start));
            if (!part.empty()) output.push_back(part);
            start = i + 1;
        }
    }
}

static bool expandSelectorGroups(
    const std::string& input,
    std::vector<std::string>& output,
    size_t depth = 0
) {
    if (depth > 32) return false;
    size_t open = std::string::npos;
    int nesting = 0;
    size_t close = std::string::npos;
    for (size_t i = 0; i < input.size(); ++i) {
        if (input[i] == '(') {
            if (nesting++ == 0) open = i;
        } else if (input[i] == ')') {
            if (nesting == 0) return false;
            if (--nesting == 0) { close = i; break; }
        }
    }
    if (nesting != 0) return false;
    if (open == std::string::npos) {
        output.push_back(trimScopeToken(input));
        return true;
    }

    std::vector<std::string> alternatives;
    splitSelectorAlternatives(input.substr(open + 1, close - open - 1), alternatives);
    if (alternatives.empty()) return false;
    const std::string prefix = input.substr(0, open);
    const std::string suffix = input.substr(close + 1);
    for (const auto& alternative : alternatives) {
        std::vector<std::string> expanded;
        if (!expandSelectorGroups(prefix + alternative + suffix, expanded, depth + 1)) return false;
        output.insert(output.end(), expanded.begin(), expanded.end());
        if (output.size() > 256) return false;
    }
    return true;
}

static int textMateSelectorSpecificity(const std::string& selector, const std::vector<std::string>& scopes) {
    std::vector<std::string> top_level;
    splitSelectorAlternatives(selector, top_level);
    int best = -1;
    for (const auto& alternative : top_level) {
        std::vector<std::string> expanded;
        if (!expandSelectorGroups(alternative, expanded)) continue;
        for (std::string candidate : expanded) {
            candidate = trimScopeToken(candidate);
            if (candidate.rfind("L:", 0) == 0 || candidate.rfind("R:", 0) == 0) {
                candidate.erase(0, 2);
            }
            best = std::max(best, matchOneTextMateSelector(candidate, scopes));
        }
    }
    return best;
}

static std::vector<std::string> buildStyleScopeStack(
    const TshGrammar& grammar, const TshStyle& style
) {
    std::vector<std::string> scopes;
    if (!grammar.id.empty()) scopes.push_back("source." + grammar.id);
    std::vector<const TshStyle*> chain;
    const TshStyle* current = &style;
    std::unordered_map<std::string, bool> seen;
    while (current && seen.emplace(current->name, true).second) {
        chain.push_back(current);
        if (current->parent.empty()) break;
        auto parent = std::find_if(grammar.styles.begin(), grammar.styles.end(),
            [&](const TshStyle& candidate) { return candidate.name == current->parent; });
        current = parent == grammar.styles.end() ? nullptr : &*parent;
    }
    std::reverse(chain.begin(), chain.end());
    for (const TshStyle* entry : chain) {
        std::istringstream stream(entry->scope);
        std::string scope;
        while (stream >> scope) scopes.push_back(scope);
    }
    return scopes;
}

static bool decodePatternUtf8ToUtf16(const std::string& input, std::vector<uint16_t>& output) {
    output.clear();
    output.reserve(input.size());
    for (size_t i = 0; i < input.size();) {
        const uint8_t first = static_cast<uint8_t>(input[i++]);
        uint32_t codepoint = 0;
        size_t continuation_count = 0;
        if (first <= 0x7F) {
            codepoint = first;
        } else if ((first & 0xE0) == 0xC0) {
            codepoint = first & 0x1F;
            continuation_count = 1;
        } else if ((first & 0xF0) == 0xE0) {
            codepoint = first & 0x0F;
            continuation_count = 2;
        } else if ((first & 0xF8) == 0xF0) {
            codepoint = first & 0x07;
            continuation_count = 3;
        } else {
            return false;
        }
        if (i + continuation_count > input.size()) return false;
        bool modified_utf8_null = false;
        for (size_t n = 0; n < continuation_count; ++n) {
            const uint8_t next = static_cast<uint8_t>(input[i++]);
            if (n == 0 && first == 0xC0 && next == 0x80) modified_utf8_null = true;
            if ((next & 0xC0) != 0x80) return false;
            codepoint = (codepoint << 6) | (next & 0x3F);
        }
        // JNI GetStringUTFChars uses modified UTF-8 for UTF-16 surrogate
        // code units. Preserve those code units while also accepting standard
        // four-byte UTF-8 supplied by source files.
        if (codepoint <= 0xFFFF) {
            if (codepoint < 0x80 && continuation_count != 0 && !modified_utf8_null) return false;
            if (codepoint < 0x800 && continuation_count == 2) return false;
            if (codepoint < 0x10000 && continuation_count == 3) return false;
            output.push_back(static_cast<uint16_t>(codepoint));
        } else if (codepoint <= 0x10FFFF) {
            codepoint -= 0x10000;
            output.push_back(static_cast<uint16_t>(0xD800 + (codepoint >> 10)));
            output.push_back(static_cast<uint16_t>(0xDC00 + (codepoint & 0x3FF)));
        } else {
            return false;
        }
    }
    return true;
}

static std::string escapeOnigurumaLiteral(const std::string& value) {
    static const std::string metacharacters = R"(\\.^$|()[]{}*+?)";
    std::string escaped;
    escaped.reserve(value.size() * 2);
    for (char ch : value) {
        if (metacharacters.find(ch) != std::string::npos) escaped.push_back('\\');
        escaped.push_back(ch);
    }
    return escaped;
}

static bool expandMonarchVariables(
    const std::string& input,
    const std::unordered_map<std::string, std::string>& variables,
    std::unordered_map<std::string, bool>& active,
    std::string& output
) {
    output.clear();
    for (size_t i = 0; i < input.size();) {
        const char ch = input[i];
        if (ch == '\\') {
            output.push_back(ch);
            ++i;
            if (i < input.size()) output.push_back(input[i++]);
            continue;
        }
        if (ch == '[') {
            const size_t start = i++;
            bool escaped = false;
            while (i < input.size()) {
                const char current = input[i++];
                if (escaped) { escaped = false; continue; }
                if (current == '\\') { escaped = true; continue; }
                if (current == ']') break;
            }
            output.append(input, start, i - start);
            continue;
        }
        if (ch == '@' && i + 1 < input.size() &&
            (std::isalpha(static_cast<unsigned char>(input[i + 1])) || input[i + 1] == '_')) {
            size_t end = i + 2;
            while (end < input.size() &&
                (std::isalnum(static_cast<unsigned char>(input[end])) || input[end] == '_')) ++end;
            const std::string name = input.substr(i + 1, end - i - 1);
            const auto found = variables.find(name);
            if (found == variables.end() || active[name]) return false;
            active[name] = true;
            std::string expanded;
            if (!expandMonarchVariables(found->second, variables, active, expanded)) return false;
            active.erase(name);
            output += "(?:" + expanded + ")";
            i = end;
            continue;
        }
        output.push_back(ch);
        ++i;
    }
    return true;
}

static bool validateNumericCaptureGroups(
    regex_t* regex,
    const std::unordered_map<int, uint16_t>& groups
) {
    if (!regex) return groups.empty();
    const int capture_count = onig_number_of_captures(regex);
    for (const auto& entry : groups) {
        // Group zero is the complete match; positive groups must exist.
        if (entry.first < 0 || entry.first > capture_count) {
            g_tsh_last_error_detail = "capture group index is out of range: " +
                std::to_string(entry.first) + " (pattern has " +
                std::to_string(capture_count) + " capture groups)";
            return false;
        }
    }
    return true;
}

static bool validateNamedCaptureGroups(
    regex_t* regex,
    const std::unordered_map<std::string, uint16_t>& groups
) {
    if (!regex) return groups.empty();
    for (const auto& entry : groups) {
        // DSL identifiers are UTF-8; Oniguruma patterns are compiled as UTF-16LE.
        // Named group identifiers are ASCII-compatible, so widen each byte here.
        std::vector<uint16_t> name;
        name.reserve(entry.first.size());
        for (unsigned char ch : entry.first) name.push_back(static_cast<uint16_t>(ch));
        const int group = onig_name_to_backref_number(
            regex,
            reinterpret_cast<const UChar*>(name.data()),
            reinterpret_cast<const UChar*>(name.data() + name.size()),
            nullptr
        );
        if (group < 0) {
            g_tsh_last_error_detail = "named capture group does not exist: " + entry.first;
            return false;
        }
    }
    return true;
}

static regex_t* compileOnigurumaUtf16(const std::string& pattern_str, OnigOptionType options = ONIG_OPTION_DEFAULT) {
    if (pattern_str.empty()) return nullptr;
    std::vector<uint16_t> u16;
    if (!decodePatternUtf8ToUtf16(pattern_str, u16)) {
        g_tsh_last_error_detail = "regex contains invalid UTF-8";
        LOGE("WindbellTSH", "Invalid UTF-8 in Oniguruma pattern: %s", pattern_str.c_str());
        return nullptr;
    }
    regex_t* reg = nullptr;
    OnigErrorInfo einfo;
    int r = onig_new(
        &reg,
        reinterpret_cast<const UChar*>(u16.data()),
        reinterpret_cast<const UChar*>(u16.data() + u16.size()),
        options,
        ONIG_ENCODING_UTF16_LE,
        ONIG_SYNTAX_DEFAULT,
        &einfo
    );
    if (r != ONIG_NORMAL) {
        UChar err[ONIG_MAX_ERROR_MESSAGE_LEN] = {};
        onig_error_code_to_str(err, r, &einfo);
        std::string message;
        for (size_t i = 0; i < ONIG_MAX_ERROR_MESSAGE_LEN && err[i] != 0; ++i) {
            const uint8_t ch = static_cast<uint8_t>(err[i]);
            message.push_back(ch < 0x80 ? static_cast<char>(ch) : '?');
        }
        g_tsh_last_error_detail = "Oniguruma regex error: " + message;
        LOGE("WindbellTSH", "Oniguruma UTF-16 Regex Compile Failed: %s, pat: %s",
             message.c_str(), pattern_str.c_str());
        return nullptr;
    }
    return reg;
}

std::shared_ptr<TshGrammar> TshCompiler::compileInternal(const std::string& source, const TSLanguage* ts_lang) {
    auto grammar = std::make_shared<TshGrammar>();
    std::unordered_map<std::string, std::string> defines_map;
    std::string dynamic_query_accumulator;
    std::unordered_map<std::string, bool> tree_sitter_query_ids;

    uint32_t global_style_counter = 0;
    bool style_id_overflow = false;
    auto ensureStyleId = [&](const std::string& name) -> uint16_t {
        auto it = grammar->style_name_to_id.find(name);
        if (it != grammar->style_name_to_id.end()) return it->second;
        if (global_style_counter > std::numeric_limits<uint16_t>::max()) {
            style_id_overflow = true;
            return 0;
        }
        const uint16_t id = static_cast<uint16_t>(global_style_counter++);
        grammar->style_name_to_id.emplace(name, id);
        return id;
    };
    TshLexer lexer(source);
    uint32_t regex_declaration_order = 0;
    uint32_t ast_declaration_order = 0;
    std::unordered_map<std::string, bool> regex_rule_ids;

    std::function<bool(CompiledRegexRule&)> parseRegexRule;
    parseRegexRule = [&](CompiledRegexRule& rule) -> bool {
        CompiledRegexRuleGuard rule_guard(rule);
        rule.regex_options = grammar->default_regex_options;
        std::string raw_match_pattern;
        std::string raw_start_pattern;
        std::string raw_end_pattern;
        while (true) {
            auto item = lexer.nextToken();
            if (item.type == TshLexer::TokenType::TOK_RBRACE) break;
            if (item.type == TshLexer::TokenType::TOK_EOF) return false;
            if (item.type == TshLexer::TokenType::TOK_COMMA) continue;

            if (item.type == TshLexer::TokenType::TOK_NUMBER) {
                int group_index = -1;
                try {
                    size_t consumed = 0;
                    group_index = std::stoi(item.text, &consumed);
                    if (consumed != item.text.size() || group_index < 0) return false;
                } catch (...) { return false; }
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto style_name = lexer.nextToken();
                if (style_name.type != TshLexer::TokenType::TOK_STRING &&
                    style_name.type != TshLexer::TokenType::TOK_IDENT) return false;
                rule.group_style_map[group_index] = ensureStyleId(style_name.text);
                continue;
            }

            if (item.text == "match") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto pattern = lexer.nextToken();
                if (pattern.text == "keywordsToRegex") {
                    raw_match_pattern = expandKeywordsToRegex(lexer.extractParenthesizedBlock());
                } else if (pattern.text == "include") {
                    std::string include_name = lexer.extractParenthesizedBlock();
                    include_name.erase(std::remove_if(include_name.begin(), include_name.end(),
                        [](unsigned char ch) { return std::isspace(ch) || ch == '"' || ch == '\''; }),
                        include_name.end());
                    if (!include_name.empty() && include_name.front() == '#') include_name.erase(include_name.begin());
                    auto definition = defines_map.find(include_name);
                    if (definition == defines_map.end()) return false;
                    raw_match_pattern = definition->second;
                } else if (pattern.type == TshLexer::TokenType::TOK_REGEX ||
                           pattern.type == TshLexer::TokenType::TOK_STRING) {
                    raw_match_pattern = pattern.text;
                } else {
                    return false;
                }
            } else if (item.text == "builtin") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto name = lexer.nextToken();
                auto definition = defines_map.find(name.text);
                if (definition == defines_map.end()) return false;
                raw_match_pattern = definition->second;
            } else if (item.text == "flags") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return false;
                while (true) {
                    auto flag = lexer.nextToken();
                    if (flag.type == TshLexer::TokenType::TOK_RBRACKET) break;
                    if (flag.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (flag.text == "i" || flag.text == "ignoreCase") rule.regex_options |= ONIG_OPTION_IGNORECASE;
                    else if (flag.text == "m" || flag.text == "dotAll") rule.regex_options |= ONIG_OPTION_MULTILINE;
                    else if (flag.text == "s" || flag.text == "singlelineAnchors") rule.regex_options |= ONIG_OPTION_SINGLELINE;
                    else if (flag.text == "x" || flag.text == "extended") rule.regex_options |= ONIG_OPTION_EXTEND;
                    else return false;
                }
            } else if (item.text == "start" || item.text == "begin" || item.text == "end") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto pattern = lexer.nextToken();
                const bool nested_pattern_block = pattern.type == TshLexer::TokenType::TOK_LBRACE;
                if (nested_pattern_block) {
                    auto match_key = lexer.nextToken();
                    if (match_key.text != "match" ||
                        lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                    pattern = lexer.nextToken();
                }
                std::string pattern_text;
                if (pattern.type == TshLexer::TokenType::TOK_IDENT && pattern.text == "include") {
                    const std::string include_argument = lexer.extractParenthesizedBlock();
                    if (include_argument.empty()) return false;
                    pattern_text = "include(" + include_argument + ")";
                } else if (pattern.type == TshLexer::TokenType::TOK_REGEX ||
                           pattern.type == TshLexer::TokenType::TOK_STRING) {
                    pattern_text = pattern.text;
                } else {
                    return false;
                }
                if (nested_pattern_block &&
                    lexer.nextToken().type != TshLexer::TokenType::TOK_RBRACE) return false;
                rule.type = RegexRuleType::BLOCK;
                if (item.text == "end") raw_end_pattern = pattern_text;
                else raw_start_pattern = pattern_text;
            } else if (item.text == "style" || item.text == "name" || item.text == "contentName") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto style_name = lexer.nextToken();
                if (style_name.type != TshLexer::TokenType::TOK_STRING &&
                    style_name.type != TshLexer::TokenType::TOK_IDENT) return false;
                if (item.text == "contentName") {
                    rule.content_style_id = ensureStyleId(style_name.text);
                    rule.has_content_style = true;
                } else {
                    rule.style_id = ensureStyleId(style_name.text);
                    rule.has_style = true;
                }
            } else if (item.text == "id") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto value = lexer.nextToken();
                if (value.type != TshLexer::TokenType::TOK_STRING &&
                    value.type != TshLexer::TokenType::TOK_IDENT) return false;
                rule.id = value.text;
            } else if (item.text == "priority") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto value = lexer.nextToken();
                if (value.type != TshLexer::TokenType::TOK_NUMBER) return false;
                try {
                    size_t consumed = 0;
                    rule.priority = std::stoi(value.text, &consumed);
                    if (consumed != value.text.size()) return false;
                } catch (...) { return false; }
            } else if (item.text == "enabled") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto value = lexer.nextToken();
                if (value.text == "true") rule.enabled = true;
                else if (value.text == "false") rule.enabled = false;
                else return false;
            } else if (item.text == "overlap") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto value = lexer.nextToken();
                if (value.text == "allow") rule.allow_overlap = true;
                else if (value.text == "deny") rule.allow_overlap = false;
                else return false;
            } else if (item.text == "contains" || item.text == "patterns" ||
                       item.text == "captures" || item.text == "beginCaptures" ||
                       item.text == "startCaptures" || item.text == "endCaptures") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto open = lexer.nextToken();
                const bool capture_block = item.text == "captures" ||
                    item.text == "beginCaptures" || item.text == "startCaptures" ||
                    item.text == "endCaptures";
                const auto expected_open = capture_block ?
                    TshLexer::TokenType::TOK_LBRACE : TshLexer::TokenType::TOK_LBRACKET;
                const auto expected_close = capture_block ?
                    TshLexer::TokenType::TOK_RBRACE : TshLexer::TokenType::TOK_RBRACKET;
                if (open.type != expected_open) return false;

                while (true) {
                    auto child_token = lexer.nextToken();
                    if (child_token.type == expected_close) break;
                    if (child_token.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (child_token.type == TshLexer::TokenType::TOK_EOF) return false;
                    if (capture_block) {
                        if (child_token.type != TshLexer::TokenType::TOK_NUMBER &&
                            child_token.type != TshLexer::TokenType::TOK_IDENT &&
                            child_token.type != TshLexer::TokenType::TOK_STRING) return false;
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                        auto style_name = lexer.nextToken();
                        auto* numeric_map = &rule.group_style_map;
                        auto* named_map = &rule.named_group_style_map;
                        if (item.text == "beginCaptures" || item.text == "startCaptures") {
                            numeric_map = &rule.start_group_style_map;
                            named_map = &rule.start_named_group_style_map;
                        } else if (item.text == "endCaptures") {
                            numeric_map = &rule.end_group_style_map;
                            named_map = &rule.end_named_group_style_map;
                        }
                        if (style_name.type != TshLexer::TokenType::TOK_STRING &&
                            style_name.type != TshLexer::TokenType::TOK_IDENT) return false;
                        if (child_token.type == TshLexer::TokenType::TOK_NUMBER) {
                            try {
                                size_t consumed = 0;
                                const int group = std::stoi(child_token.text, &consumed);
                                if (consumed != child_token.text.size() || group < 0) return false;
                                if (!numeric_map->emplace(group, ensureStyleId(style_name.text)).second) {
                                    g_tsh_last_error_detail = "duplicate numeric capture declaration: " +
                                        std::to_string(group);
                                    return false;
                                }
                            } catch (...) { return false; }
                        } else {
                            if (!named_map->emplace(child_token.text, ensureStyleId(style_name.text)).second) {
                                g_tsh_last_error_detail = "duplicate named capture declaration: " +
                                    child_token.text;
                                return false;
                            }
                        }
                    } else {
                        if (child_token.type != TshLexer::TokenType::TOK_LBRACE) return false;
                        CompiledRegexRule nested;
                        nested.declaration_order = regex_declaration_order++;
                        if (!parseRegexRule(nested)) return false;
                        rule.nested_rules.push_back(std::move(nested));
                    }
                }
            } else {
                return false;
            }
        }

        auto expand_pattern = [&](std::string& pattern) -> bool {
            if (pattern.empty()) return true;
            std::unordered_map<std::string, bool> active;
            std::string expanded;
            if (!expandRepositoryIncludes(pattern, defines_map, active, expanded)) return false;
            pattern = std::move(expanded);
            return true;
        };
        if (!expand_pattern(raw_match_pattern) || !expand_pattern(raw_start_pattern) ||
            !expand_pattern(raw_end_pattern)) return false;
        if (!raw_match_pattern.empty()) {
            rule.onig_start = compileOnigurumaUtf16(raw_match_pattern, rule.regex_options);
            if (!rule.onig_start) return false;
        }
        if (!raw_start_pattern.empty()) {
            rule.onig_start = compileOnigurumaUtf16(raw_start_pattern, rule.regex_options);
            if (!rule.onig_start) return false;
        }
        if (!raw_end_pattern.empty()) {
            rule.onig_end = compileOnigurumaUtf16(raw_end_pattern, rule.regex_options);
            if (!rule.onig_end) return false;
        }

        if (!rule.id.empty() && !regex_rule_ids.emplace(rule.id, true).second) return false;
        // Reject invalid numeric capture references at compile time. Previously
        // these references were silently ignored by the runtime, making a typo in
        // a .tsh file look like a successful but partially styled grammar.
        if (!validateNumericCaptureGroups(rule.onig_start, rule.group_style_map) ||
            !validateNumericCaptureGroups(rule.onig_start, rule.start_group_style_map) ||
            !validateNumericCaptureGroups(rule.onig_end, rule.end_group_style_map) ||
            !validateNamedCaptureGroups(rule.onig_start, rule.named_group_style_map) ||
            !validateNamedCaptureGroups(rule.onig_start, rule.start_named_group_style_map) ||
            !validateNamedCaptureGroups(rule.onig_end, rule.end_named_group_style_map)) return false;

        const bool has_delimiter_captures = !rule.start_group_style_map.empty() ||
            !rule.start_named_group_style_map.empty() || !rule.end_group_style_map.empty() ||
            !rule.end_named_group_style_map.empty();
        // "captures" is the whole-match rule capture map. A BLOCK has two
        // independent delimiter expressions, so accepting this map would be
        // ambiguous and, historically, silently ignored by the block runtime.
        // Require beginCaptures/startCaptures and endCaptures instead.
        const bool has_generic_captures = !rule.group_style_map.empty() ||
            !rule.named_group_style_map.empty();
        if (rule.type == RegexRuleType::BLOCK && has_generic_captures) {
            g_tsh_last_error_detail =
                "captures is only valid on match rules; use beginCaptures/startCaptures or endCaptures on block rules";
            return false;
        }
        const bool valid = rule.type == RegexRuleType::BLOCK ?
            ((rule.has_style || rule.has_content_style || has_delimiter_captures) &&
             rule.onig_start && rule.onig_end) :
            (rule.onig_start != nullptr && !rule.has_content_style && !has_delimiter_captures);
        if (valid) rule_guard.release();
        return valid;
    };

    TshLexer::Token tok = lexer.nextToken();
    bool versioned_document = false;
    bool versioned_document_closed = false;
    std::unordered_map<std::string, bool> seen_root_sections;
    auto mark_root_section = [&](const std::string& name) {
        return seen_root_sections.emplace(name, true).second;
    };
    if (tok.type == TshLexer::TokenType::TOK_IDENT && tok.text == "tsh") {
        const auto version = lexer.nextToken();
        if (version.type != TshLexer::TokenType::TOK_NUMBER || version.text != "2") return nullptr;
        if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
        versioned_document = true;
        tok = lexer.nextToken();
    }

    while (tok.type != TshLexer::TokenType::TOK_EOF &&
           !(versioned_document && tok.type == TshLexer::TokenType::TOK_RBRACE)) {
        if (versioned_document && tok.type == TshLexer::TokenType::TOK_EOF) break;
        if (tok.type == TshLexer::TokenType::TOK_INVALID) return nullptr;
        if (tok.type == TshLexer::TokenType::TOK_IDENT) {

            // Legacy shorthand retained with explicit, validated semantics.
            if (tok.text == "ignoreCase") {
                if (!mark_root_section("ignoreCase")) return nullptr;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                auto value = lexer.nextToken();
                if (value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                if (value.text == "true") grammar->default_regex_options |= ONIG_OPTION_IGNORECASE;
                else if (value.text != "false") return nullptr;
            }

            // Language-wide regex execution semantics.
            else if (tok.text == "matching") {
                if (!mark_root_section("matching")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                while (true) {
                    auto key = lexer.nextToken();
                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (key.type == TshLexer::TokenType::TOK_EOF ||
                        lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                    auto value = lexer.nextToken();
                    if (value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                    const bool enabled = value.text == "true";
                    if (value.text != "true" && value.text != "false") return nullptr;
                    if (key.text == "caseSensitive") {
                        if (enabled) grammar->default_regex_options &= ~ONIG_OPTION_IGNORECASE;
                        else grammar->default_regex_options |= ONIG_OPTION_IGNORECASE;
                    } else if (key.text == "multiline") {
                        if (enabled) grammar->default_regex_options &= ~ONIG_OPTION_SINGLELINE;
                        else grammar->default_regex_options |= ONIG_OPTION_SINGLELINE;
                    } else if (key.text == "dotAll") {
                        if (enabled) grammar->default_regex_options |= ONIG_OPTION_MULTILINE;
                        else grammar->default_regex_options &= ~ONIG_OPTION_MULTILINE;
                    } else if (key.text == "extended") {
                        if (enabled) grammar->default_regex_options |= ONIG_OPTION_EXTEND;
                        else grammar->default_regex_options &= ~ONIG_OPTION_EXTEND;
                    } else if (key.text == "unicode") {
                        // Oniguruma is always compiled in UTF-16LE mode. Reject
                        // disabling Unicode rather than silently changing encoding.
                        if (!enabled) return nullptr;
                    } else {
                        return nullptr;
                    }
                }
            }

            // Language identity and editor/file association metadata.
            else if (tok.text == "language") {
                if (!mark_root_section("language")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                auto read_values = [&](std::vector<std::string>& values) -> bool {
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return false;
                    while (true) {
                        auto value = lexer.nextToken();
                        if (value.type == TshLexer::TokenType::TOK_RBRACKET) return true;
                        if (value.type == TshLexer::TokenType::TOK_COMMA) continue;
                        if (value.type != TshLexer::TokenType::TOK_STRING &&
                            value.type != TshLexer::TokenType::TOK_IDENT) return false;
                        values.push_back(value.text);
                    }
                };
                while (true) {
                    auto key = lexer.nextToken();
                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (key.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                    if (key.text == "id" || key.text == "name" || key.text == "firstLine") {
                        auto value = lexer.nextToken();
                        if (key.text == "id") grammar->id = value.text;
                        else if (key.text == "name") grammar->name = value.text;
                        else if (value.type != TshLexer::TokenType::TOK_REGEX) return nullptr;
                        else {
                            regex_t* validation = compileOnigurumaUtf16(value.text);
                            if (!validation) return nullptr;
                            if (grammar->first_line_regex) onig_free(grammar->first_line_regex);
                            grammar->first_line_regex = validation;
                            grammar->first_line_pattern = value.text;
                        }
                    } else if (key.text == "extensions") {
                        std::vector<std::string> values;
                        if (!read_values(values)) return nullptr;
                        for (auto value : values) {
                            if (!value.empty() && value.front() == '.') value.erase(value.begin());
                            if (!value.empty()) grammar->file_extensions.push_back(std::move(value));
                        }
                    } else if (key.text == "aliases") {
                        if (!read_values(grammar->aliases)) return nullptr;
                    } else if (key.text == "mimetypes" || key.text == "mimeTypes") {
                        if (!read_values(grammar->mime_types)) return nullptr;
                    } else {
                        return nullptr;
                    }
                }
            }

            // =================================================================
            // 1. 解析语法元信息: name: ["Java", ".java"]
            // =================================================================
            else if (tok.text == "name") {
                if (!mark_root_section("name")) return nullptr;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                    lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                while (true) {
                    auto t = lexer.nextToken();
                    if (t.type == TshLexer::TokenType::TOK_RBRACKET) break;
                    if (t.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (t.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                    if (grammar->name.empty()) grammar->name = t.text;
                    if (!t.text.empty() && t.text[0] == '.') {
                        grammar->file_extensions.push_back(t.text.substr(1));
                    }
                }
            }

            // =================================================================
            // 2. 解析多主题方案: schemes { name { light [...], dark [...] } }
            // =================================================================
            else if (tok.text == "schemes") {
                if (!mark_root_section("schemes")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;

                while (true) {
                    auto scheme_name_tok = lexer.nextToken();
                    if (scheme_name_tok.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (scheme_name_tok.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                    if (scheme_name_tok.type != TshLexer::TokenType::TOK_IDENT &&
                        scheme_name_tok.type != TshLexer::TokenType::TOK_STRING) return nullptr;

                    const std::string scheme_name = scheme_name_tok.text;
                    if (grammar->schemes.find(scheme_name) != grammar->schemes.end()) return nullptr;
                    // A real schemes block takes precedence over the legacy
                    // compatibility palette named "default".
                    if (grammar->default_scheme_name.empty() ||
                        (grammar->default_scheme_name == "default" &&
                         grammar->schemes.find("default") != grammar->schemes.end())) {
                        grammar->default_scheme_name = scheme_name;
                    }

                    auto scheme_open = lexer.nextToken();
                    if (scheme_open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;

                    ColorScheme scheme;
                    scheme.name = scheme_name;
                    while (true) {
                        auto mode_tok = lexer.nextToken();
                        if (mode_tok.type == TshLexer::TokenType::TOK_RBRACE) break;
                        if (mode_tok.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                        if (mode_tok.text == "extends") {
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            auto parent = lexer.nextToken();
                            if (parent.type != TshLexer::TokenType::TOK_STRING &&
                                parent.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                            scheme.extends = parent.text;
                            continue;
                        }

                        if (mode_tok.text == "scopeRules") {
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                                lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                            while (true) {
                                auto entry = lexer.nextToken();
                                if (entry.type == TshLexer::TokenType::TOK_RBRACKET) break;
                                if (entry.type == TshLexer::TokenType::TOK_COMMA) continue;
                                if (entry.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                TshScopeColorRule scope_rule;
                                scope_rule.declaration_order = static_cast<uint32_t>(scheme.scope_rules.size());
                                while (true) {
                                    auto key = lexer.nextToken();
                                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                    if (key.type == TshLexer::TokenType::TOK_EOF ||
                                        lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                    auto value = lexer.nextToken();
                                    if (key.text == "selector") {
                                        if (value.type != TshLexer::TokenType::TOK_STRING &&
                                            value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                        scope_rule.selector = value.text;
                                    } else if (key.text == "light" || key.text == "dark") {
                                        if (value.type != TshLexer::TokenType::TOK_IDENT ||
                                            !isValidColorHex(value.text)) return nullptr;
                                        if (key.text == "light") {
                                            scope_rule.light_color = parseColorHex(value.text);
                                            scope_rule.has_light_color = true;
                                        } else {
                                            scope_rule.dark_color = parseColorHex(value.text);
                                            scope_rule.has_dark_color = true;
                                        }
                                    } else {
                                        return nullptr;
                                    }
                                }
                                if (scope_rule.selector.empty() ||
                                    (!scope_rule.has_light_color && !scope_rule.has_dark_color)) return nullptr;
                                scheme.scope_rules.push_back(std::move(scope_rule));
                            }
                            continue;
                        }

                        if (mode_tok.text == "ui") {
                            auto ui_open = lexer.nextToken();
                            if (ui_open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                            while (true) {
                                auto token_name = lexer.nextToken();
                                if (token_name.type == TshLexer::TokenType::TOK_RBRACE) break;
                                if (token_name.type == TshLexer::TokenType::TOK_COMMA) continue;
                                if (token_name.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                                if (token_name.text == "light" || token_name.text == "dark") {
                                    const bool light_mode = token_name.text == "light";
                                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                    while (true) {
                                        auto key = lexer.nextToken();
                                        if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                        if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                        if (key.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                        auto value = lexer.nextToken();
                                        if (value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                        if (value.type != TshLexer::TokenType::TOK_IDENT || !isValidColorHex(value.text)) return nullptr;
                                        auto& target = light_mode ? scheme.ui_light_colors : scheme.ui_dark_colors;
                                        target[key.text] = parseColorHex(value.text);
                                    }
                                } else {
                                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                    auto value = lexer.nextToken();
                                    if (value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                    if (value.type != TshLexer::TokenType::TOK_IDENT || !isValidColorHex(value.text)) return nullptr;
                                    scheme.ui_colors[token_name.text] = parseColorHex(value.text);
                                }
                            }
                            continue;
                        }

                        const bool is_light = mode_tok.text == "light";
                        const bool is_dark = mode_tok.text == "dark";
                        if (!is_light && !is_dark) return nullptr;

                        auto list_open = lexer.nextToken();
                        if (list_open.type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;

                        while (true) {
                            auto item_tok = lexer.nextToken();
                            if (item_tok.type == TshLexer::TokenType::TOK_RBRACKET) break;
                            if (item_tok.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (item_tok.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                            const std::string symbol_name = item_tok.text;
                            auto separator = lexer.nextToken();
                            if (separator.type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            auto color_tok = lexer.nextToken();
                            if (color_tok.type != TshLexer::TokenType::TOK_IDENT || !isValidColorHex(color_tok.text)) return nullptr;

                            uint16_t sid = 0;
                            auto style_it = grammar->style_name_to_id.find(symbol_name);
                            if (style_it == grammar->style_name_to_id.end()) {
                                sid = ensureStyleId(symbol_name);
                            } else {
                                sid = style_it->second;
                            }

                            const uint32_t color_val = parseColorHex(color_tok.text);
                            uint16_t flags = 0;
                            while (true) {
                                auto modifier = lexer.nextToken();
                                if (modifier.type == TshLexer::TokenType::TOK_COMMA) break;
                                if (modifier.type == TshLexer::TokenType::TOK_RBRACKET) {
                                    lexer.putBack(std::move(modifier));
                                    break;
                                }
                                if (modifier.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                                if (modifier.type == TshLexer::TokenType::TOK_AT) {
                                    auto flag_tok = lexer.nextToken();
                                    if (flag_tok.text == "B" || flag_tok.text == "bold") flags |= 0x01;
                                    else if (flag_tok.text == "I" || flag_tok.text == "italic") flags |= 0x02;
                                    else if (flag_tok.text == "U" || flag_tok.text == "underline") flags |= 0x04;
                                    else if (flag_tok.text == "S" || flag_tok.text == "strike" || flag_tok.text == "strikethrough") flags |= 0x08;
                                    else if (flag_tok.text == "dim") flags |= 0x10;
                                    else return nullptr;
                                    continue;
                                }
                                // Commas are optional: the next identifier begins
                                // another style entry and must remain in the stream.
                                lexer.putBack(std::move(modifier));
                                break;
                            }

                            auto& color_entry = scheme.colors[sid];
                            if (is_light) {
                                color_entry.light_color = color_val;
                                color_entry.has_light_color = true;
                                color_entry.explicit_light_color = true;
                            } else {
                                color_entry.dark_color = color_val;
                                color_entry.has_dark_color = true;
                                color_entry.explicit_dark_color = true;
                            }
                            if (is_light) color_entry.light_font_flags |= flags;
                            else color_entry.dark_font_flags |= flags;
                        }
                    }
                    grammar->schemes[scheme_name] = std::move(scheme);
                }
            }

            else if (tok.text == "defaultScheme") {
                if (!mark_root_section("defaultScheme")) return nullptr;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                auto value = lexer.nextToken();
                if (value.type != TshLexer::TokenType::TOK_STRING &&
                    value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                grammar->default_scheme_name = value.text;
            }

            // DSL default rendering mode: false = light/day, true = dark/night.
            else if (tok.text == "isDark") {
                if (!mark_root_section("isDark")) return nullptr;
                auto separator = lexer.nextToken();
                if (separator.type != TshLexer::TokenType::TOK_COLON) return nullptr;
                auto value = lexer.nextToken();
                if (value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                if (value.text == "true") grammar->default_is_dark = true;
                else if (value.text == "false") grammar->default_is_dark = false;
                else return nullptr;
            }

            // =================================================================
            // 3. Semantic style declarations. Supports v2 object syntax and the
            // legacy [name, lightColor, darkColor, @modifier] tuple syntax.
            // =================================================================
            else if (tok.text == "styles") {
                if (!mark_root_section("styles")) return nullptr;
                auto styles_open = lexer.nextToken();
                if (styles_open.type == TshLexer::TokenType::TOK_COLON) styles_open = lexer.nextToken();

                if (styles_open.type == TshLexer::TokenType::TOK_LBRACE) {
                    while (true) {
                        auto style_name_tok = lexer.nextToken();
                        if (style_name_tok.type == TshLexer::TokenType::TOK_RBRACE) break;
                        if (style_name_tok.type == TshLexer::TokenType::TOK_COMMA) continue;
                        if (style_name_tok.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                        const std::string style_name = style_name_tok.text;
                        if (grammar->style_definitions.find(style_name) != grammar->style_definitions.end()) return nullptr;
                        const uint16_t style_id = ensureStyleId(style_name);
                        auto style_open = lexer.nextToken();
                        if (style_open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;

                        TshStyle definition;
                        definition.name = style_name;
                        definition.style_id = style_id;
                        while (true) {
                            auto field = lexer.nextToken();
                            if (field.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (field.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (field.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                            auto separator = lexer.nextToken();
                            if (separator.type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            if (field.text == "tags") {
                                auto array_open = lexer.nextToken();
                                if (array_open.type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                                while (true) {
                                    auto tag = lexer.nextToken();
                                    if (tag.type == TshLexer::TokenType::TOK_RBRACKET) break;
                                    if (tag.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                                    if (tag.type != TshLexer::TokenType::TOK_COMMA) {
                                        definition.tags.push_back(tag.text);
                                    }
                                }
                            } else if (field.text == "scope" || field.text == "description" ||
                                       field.text == "parent") {
                                auto value = lexer.nextToken();
                                if (value.type != TshLexer::TokenType::TOK_STRING &&
                                    value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                if (field.text == "scope") definition.scope = value.text;
                                else if (field.text == "description") definition.description = value.text;
                                else definition.parent = value.text;
                            } else if (field.text == "deprecated") {
                                auto value = lexer.nextToken();
                                if (value.text == "true") definition.deprecated = true;
                                else if (value.text == "false") definition.deprecated = false;
                                else return nullptr;
                            } else if (field.text == "metadata") {
                                if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                while (true) {
                                    auto key = lexer.nextToken();
                                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                    if (key.type != TshLexer::TokenType::TOK_IDENT &&
                                        key.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                    auto value = lexer.nextToken();
                                    if (value.type != TshLexer::TokenType::TOK_IDENT &&
                                        value.type != TshLexer::TokenType::TOK_STRING &&
                                        value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                    definition.metadata[key.text] = value.text;
                                }
                            } else {
                                return nullptr;
                            }
                        }
                        grammar->style_definitions[style_name] = std::move(definition);
                    }
                } else if (styles_open.type == TshLexer::TokenType::TOK_LBRACKET) {
                    ColorScheme legacy_scheme;
                    legacy_scheme.name = "default";
                    if (grammar->default_scheme_name.empty()) grammar->default_scheme_name = "default";

                    while (true) {
                        auto style_name_tok = lexer.nextToken();
                        if (style_name_tok.type == TshLexer::TokenType::TOK_RBRACKET) break;
                        if (style_name_tok.type == TshLexer::TokenType::TOK_COMMA) continue;
                        if (style_name_tok.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                        const std::string style_name = style_name_tok.text;
                        const uint16_t style_id = ensureStyleId(style_name);
                        auto light_sep = lexer.nextToken();
                        if (light_sep.type != TshLexer::TokenType::TOK_COMMA) return nullptr;
                        auto light_tok = lexer.nextToken();
                        if (light_tok.type != TshLexer::TokenType::TOK_IDENT || !isValidColorHex(light_tok.text)) return nullptr;
                        auto dark_sep = lexer.nextToken();
                        if (dark_sep.type != TshLexer::TokenType::TOK_COMMA) return nullptr;
                        auto dark_tok = lexer.nextToken();
                        if (dark_tok.type != TshLexer::TokenType::TOK_IDENT || !isValidColorHex(dark_tok.text)) return nullptr;

                        SchemeColor color;
                        color.light_color = parseColorHex(light_tok.text);
                        color.dark_color = parseColorHex(dark_tok.text);
                        color.has_light_color = true;
                        color.has_dark_color = true;

                        auto modifier = lexer.nextToken();
                        if (modifier.type == TshLexer::TokenType::TOK_COMMA) {
                            modifier = lexer.nextToken();
                        }
                        while (modifier.type == TshLexer::TokenType::TOK_AT) {
                            auto flag = lexer.nextToken();
                            if (flag.text == "B" || flag.text == "bold") {
                                color.light_font_flags |= 0x01;
                                color.dark_font_flags |= 0x01;
                            }
                            else if (flag.text == "I" || flag.text == "italic") {
                                color.light_font_flags |= 0x02;
                                color.dark_font_flags |= 0x02;
                            } else if (flag.text == "U" || flag.text == "underline") {
                                color.light_font_flags |= 0x04;
                                color.dark_font_flags |= 0x04;
                            } else if (flag.text == "S" || flag.text == "strike" || flag.text == "strikethrough") {
                                color.light_font_flags |= 0x08;
                                color.dark_font_flags |= 0x08;
                            } else if (flag.text == "dim") {
                                color.light_font_flags |= 0x10;
                                color.dark_font_flags |= 0x10;
                            }
                            modifier = lexer.nextToken();
                            if (modifier.type == TshLexer::TokenType::TOK_COMMA) {
                                modifier = lexer.nextToken();
                            }
                        }
                        if (modifier.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                        // Leave the next tuple or closing bracket for the outer loop.
                        lexer.putBack(std::move(modifier));
                        legacy_scheme.colors[style_id] = color;
                    }
                    grammar->schemes[legacy_scheme.name] = std::move(legacy_scheme);
                } else {
                    return nullptr;
                }
            }

            // =================================================================
            // 3. 解析宏复用: defines: [ "ident": /[a-zA-Z_$].../ ]
            // =================================================================
            else if (tok.text == "defines") {
                if (!mark_root_section("defines")) return nullptr;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                    lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                while (true) {
                    auto dt = lexer.nextToken();
                    if (dt.type == TshLexer::TokenType::TOK_RBRACKET) break;
                    if (dt.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (dt.type == TshLexer::TokenType::TOK_EOF ||
                        (dt.type != TshLexer::TokenType::TOK_STRING &&
                         dt.type != TshLexer::TokenType::TOK_IDENT)) return nullptr;
                    const std::string d_name = dt.text;
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                    auto d_val = lexer.nextToken();
                    if (d_val.type != TshLexer::TokenType::TOK_REGEX &&
                        d_val.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                    if (!defines_map.emplace(d_name, d_val.text).second) return nullptr;
                }
            }

            // TextMate-compatible named repository. Entries are regex fragments
            // addressable through include("#name") from match/begin/end patterns.
            // Keep the same expansion and cycle detection path as legacy defines.
            else if (tok.text == "repository") {
                if (!mark_root_section("repository")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                const auto close_type = open.type == TshLexer::TokenType::TOK_LBRACE
                    ? TshLexer::TokenType::TOK_RBRACE
                    : open.type == TshLexer::TokenType::TOK_LBRACKET
                        ? TshLexer::TokenType::TOK_RBRACKET
                        : TshLexer::TokenType::TOK_INVALID;
                if (close_type == TshLexer::TokenType::TOK_INVALID) return nullptr;
                while (true) {
                    auto entry = lexer.nextToken();
                    if (entry.type == close_type) break;
                    if (entry.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (entry.type == TshLexer::TokenType::TOK_EOF ||
                        (entry.type != TshLexer::TokenType::TOK_IDENT &&
                         entry.type != TshLexer::TokenType::TOK_STRING)) return nullptr;
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                    auto pattern = lexer.nextToken();
                    std::string repository_value;
                    if (pattern.type == TshLexer::TokenType::TOK_REGEX ||
                        pattern.type == TshLexer::TokenType::TOK_STRING) {
                        repository_value = pattern.text;
                    } else if (pattern.type == TshLexer::TokenType::TOK_IDENT &&
                               pattern.text == "include") {
                        const std::string target = lexer.extractParenthesizedBlock();
                        if (target.empty()) return nullptr;
                        repository_value = "include(" + target + ")";
                    } else {
                        return nullptr;
                    }
                    if (repository_value.empty() ||
                        !defines_map.emplace(entry.text, repository_value).second) return nullptr;
                }
            }

            // Monarch-compatible tokenizer states. Rules are compiled to the
            // same Oniguruma UTF-16 engine used by TextMate-style regex patterns.
            else if (tok.text == "monarch") {
                if (!mark_root_section("monarch")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                std::unordered_map<std::string, bool> seen_monarch_fields;
                bool monarch_tokenizer_seen = false;
                uint32_t monarch_default_options = grammar->default_regex_options;
                while (true) {
                    auto field = lexer.nextToken();
                    if (field.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (field.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (field.type == TshLexer::TokenType::TOK_EOF ||
                        !seen_monarch_fields.emplace(field.text, true).second) return nullptr;
                    if (field.text == "variables") {
                        if (monarch_tokenizer_seen ||
                            lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                        while (true) {
                            auto variable = lexer.nextToken();
                            if (variable.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (variable.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (variable.type != TshLexer::TokenType::TOK_IDENT ||
                                lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            auto value = lexer.nextToken();
                            if ((value.type != TshLexer::TokenType::TOK_REGEX &&
                                 value.type != TshLexer::TokenType::TOK_STRING) ||
                                value.text.empty() ||
                                !grammar->monarch_variables.emplace(variable.text, value.text).second) return nullptr;
                        }
                    } else if (field.text == "tokenPostfix") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        auto value = lexer.nextToken();
                        if (value.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                        grammar->monarch_token_postfix = value.text;
                    } else if (field.text == "ignoreCase") {
                        if (monarch_tokenizer_seen ||
                            lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        auto value = lexer.nextToken();
                        if (value.text == "true") monarch_default_options |= ONIG_OPTION_IGNORECASE;
                        else if (value.text == "false") monarch_default_options &= ~ONIG_OPTION_IGNORECASE;
                        else return nullptr;
                    } else if (field.text == "start" || field.text == "defaultToken") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        auto value = lexer.nextToken();
                        if ((value.type != TshLexer::TokenType::TOK_STRING &&
                             value.type != TshLexer::TokenType::TOK_IDENT) ||
                            value.text.empty()) return nullptr;
                        if (field.text == "start") {
                            grammar->monarch_start_state = value.text;
                            if (!grammar->monarch_start_state.empty() && grammar->monarch_start_state.front() == '@')
                                grammar->monarch_start_state.erase(grammar->monarch_start_state.begin());
                        } else {
                            grammar->monarch_default_style_name = value.text;
                            grammar->monarch_has_default_style = true;
                        }
                    } else if (field.text == "tokenizer") {
                        monarch_tokenizer_seen = true;
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                        while (true) {
                            auto state_name = lexer.nextToken();
                            if (state_name.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (state_name.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if ((state_name.type != TshLexer::TokenType::TOK_IDENT &&
                                 state_name.type != TshLexer::TokenType::TOK_STRING) ||
                                state_name.text.empty()) return nullptr;
                            if (grammar->monarch_states.find(state_name.text) != grammar->monarch_states.end()) return nullptr;
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                                lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                            auto& state_rules = grammar->monarch_states[state_name.text];
                            while (true) {
                                auto entry = lexer.nextToken();
                                if (entry.type == TshLexer::TokenType::TOK_RBRACKET) break;
                                if (entry.type == TshLexer::TokenType::TOK_COMMA) continue;
                                if (entry.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                MonarchRule rule;
                                MonarchRuleGuard rule_guard(rule);
                                rule.declaration_order = regex_declaration_order++;
                                uint32_t options = monarch_default_options;
                                std::string pattern;
                                std::unordered_map<std::string, bool> seen_rule_fields;
                                while (true) {
                                    auto key = lexer.nextToken();
                                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                    if (key.type == TshLexer::TokenType::TOK_EOF ||
                                        !seen_rule_fields.emplace(key.text, true).second ||
                                        lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                    auto value = lexer.nextToken();
                                    if (key.text == "match" || key.text == "regex") {
                                        if (value.type != TshLexer::TokenType::TOK_REGEX &&
                                            value.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                                        pattern = value.text;
                                    } else if (key.text == "token" || key.text == "style") {
                                        if (value.type != TshLexer::TokenType::TOK_STRING &&
                                            value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                        if (!value.text.empty()) {
                                            rule.token_name = value.text;
                                            rule.has_style = true;
                                        }
                                    } else if (key.text == "cases") {
                                        if (value.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                        bool saw_default_case = false;
                                        std::unordered_map<std::string, bool> seen_case_keys;
                                        while (true) {
                                            auto case_key = lexer.nextToken();
                                            if (case_key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                            if (case_key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                            if (saw_default_case ||
                                                (case_key.type != TshLexer::TokenType::TOK_STRING &&
                                                 case_key.type != TshLexer::TokenType::TOK_IDENT) ||
                                                !seen_case_keys.emplace(case_key.text, true).second ||
                                                lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                            auto case_token = lexer.nextToken();
                                            if ((case_token.type != TshLexer::TokenType::TOK_STRING &&
                                                 case_token.type != TshLexer::TokenType::TOK_IDENT) ||
                                                case_token.text.empty()) return nullptr;
                                            MonarchCase case_entry;
                                            case_entry.token_name = case_token.text;
                                            if (case_key.text == "@default") {
                                                case_entry.is_default = true;
                                                saw_default_case = true;
                                            } else {
                                                std::string case_pattern;
                                                if (!case_key.text.empty() && case_key.text.front() == '@') {
                                                    std::unordered_map<std::string, bool> active_variables;
                                                    if (!expandMonarchVariables(case_key.text,
                                                            grammar->monarch_variables,
                                                            active_variables, case_pattern)) return nullptr;
                                                } else {
                                                    case_pattern = escapeOnigurumaLiteral(case_key.text);
                                                }
                                                case_entry.matcher = compileOnigurumaUtf16(case_pattern, options);
                                                if (!case_entry.matcher) return nullptr;
                                            }
                                            rule.cases.push_back(std::move(case_entry));
                                        }
                                    } else if (key.text == "include") {
                                        if (value.type != TshLexer::TokenType::TOK_STRING &&
                                            value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                        rule.include_state = value.text;
                                        if (!rule.include_state.empty() && rule.include_state.front() == '@')
                                            rule.include_state.erase(rule.include_state.begin());
                                    } else if (key.text == "next" || key.text == "push" || key.text == "switchTo") {
                                        if (value.type != TshLexer::TokenType::TOK_STRING &&
                                            value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                        if (key.text == "next") rule.next_state = value.text;
                                        else if (key.text == "push") rule.push_state = value.text;
                                        else rule.switch_state = value.text;
                                    } else if (key.text == "pop" || key.text == "rematch" || key.text == "enabled") {
                                        if (value.text != "true" && value.text != "false") return nullptr;
                                        if (key.text == "pop") rule.pop = value.text == "true";
                                        else if (key.text == "rematch") rule.rematch = value.text == "true";
                                        else rule.enabled = value.text == "true";
                                    } else if (key.text == "priority") {
                                        if (value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                        try {
                                            size_t consumed = 0;
                                            rule.priority = std::stoi(value.text, &consumed);
                                            if (consumed != value.text.size()) return nullptr;
                                        } catch (...) { return nullptr; }
                                    } else if (key.text == "flags") {
                                        if (value.type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                                        while (true) {
                                            auto flag = lexer.nextToken();
                                            if (flag.type == TshLexer::TokenType::TOK_RBRACKET) break;
                                            if (flag.type == TshLexer::TokenType::TOK_COMMA) continue;
                                            if (flag.text == "i") options |= ONIG_OPTION_IGNORECASE;
                                            else if (flag.text == "m") options |= ONIG_OPTION_MULTILINE;
                                            else if (flag.text == "s") options |= ONIG_OPTION_SINGLELINE;
                                            else if (flag.text == "x") options |= ONIG_OPTION_EXTEND;
                                            else return nullptr;
                                        }
                                    } else {
                                        return nullptr;
                                    }
                                }
                                if (!pattern.empty()) {
                                    std::unordered_map<std::string, bool> active_variables;
                                    std::string expanded_pattern;
                                    if (!expandMonarchVariables(pattern, grammar->monarch_variables,
                                            active_variables, expanded_pattern)) return nullptr;
                                    rule.pattern = compileOnigurumaUtf16(expanded_pattern, options);
                                    if (!rule.pattern) return nullptr;
                                }
                                if (rule.include_state.empty() && !rule.pattern) return nullptr;
                                state_rules.push_back(std::move(rule));
                                rule_guard.release();
                            }
                        }
                    } else {
                        return nullptr;
                    }
                }
                if (grammar->monarch_states.empty()) return nullptr;
                for (auto& state : grammar->monarch_states) {
                    for (auto& rule : state.second) {
                        if (rule.has_style) {
                            rule.style_id = ensureStyleId(rule.token_name + grammar->monarch_token_postfix);
                        }
                        for (auto& case_entry : rule.cases) {
                            case_entry.style_id = ensureStyleId(case_entry.token_name + grammar->monarch_token_postfix);
                        }
                    }
                }
                // Validate every declared variable, including unused definitions,
                // so misspellings and cycles never remain latent in a compiled grammar.
                for (const auto& variable : grammar->monarch_variables) {
                    std::unordered_map<std::string, bool> active_variables;
                    std::string expanded_variable;
                    if (!expandMonarchVariables(variable.second, grammar->monarch_variables,
                            active_variables, expanded_variable)) return nullptr;
                }
            }

            // TextMate-style repository of reusable regular-expression fragments.
            // A rule may reference an entry as include("#repositoryName").
            else if (tok.text == "repository") {
                if (!mark_root_section("repository")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                while (true) {
                    auto key = lexer.nextToken();
                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (key.type != TshLexer::TokenType::TOK_IDENT &&
                        key.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                    auto pattern = lexer.nextToken();
                    if (pattern.type != TshLexer::TokenType::TOK_REGEX &&
                        pattern.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                    if (!defines_map.emplace(key.text, pattern.text).second) return nullptr;
                }
            }

            // =================================================================
            // Declarative editor metadata: comments, pairs, indentation and triggers.
            // These are data for the host editor; the native core does not hard-code them.
            // =================================================================
            else if (tok.text == "editor") {
                if (!mark_root_section("editor")) return nullptr;
                auto editor_open = lexer.nextToken();
                if (editor_open.type == TshLexer::TokenType::TOK_COLON) editor_open = lexer.nextToken();
                if (editor_open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;

                auto read_string_array = [&](std::vector<std::string>& output) -> bool {
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return false;
                    while (true) {
                        auto value = lexer.nextToken();
                        if (value.type == TshLexer::TokenType::TOK_RBRACKET) return true;
                        if (value.type == TshLexer::TokenType::TOK_COMMA) continue;
                        if (value.type != TshLexer::TokenType::TOK_STRING &&
                            value.type != TshLexer::TokenType::TOK_IDENT) return false;
                        output.push_back(value.text);
                    }
                };

                while (true) {
                    auto field = lexer.nextToken();
                    if (field.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (field.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (field.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                    if (field.text == "comments") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                        while (true) {
                            auto comment_kind = lexer.nextToken();
                            if (comment_kind.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (comment_kind.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            if (comment_kind.text == "line") {
                                if (!read_string_array(grammar->editor_metadata.line_comments)) return nullptr;
                            } else if (comment_kind.text == "block") {
                                if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                                while (true) {
                                    auto pair_token = lexer.nextToken();
                                    if (pair_token.type == TshLexer::TokenType::TOK_RBRACKET) break;
                                    if (pair_token.type == TshLexer::TokenType::TOK_COMMA) continue;
                                    if (pair_token.type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                                    lexer.putBack(std::move(pair_token));
                                    std::vector<std::string> pair;
                                    if (!read_string_array(pair)) return nullptr;
                                    if (pair.size() != 2) return nullptr;
                                    grammar->editor_metadata.block_comments.emplace_back(pair[0], pair[1]);
                                }
                            } else {
                                return nullptr;
                            }
                        }
                    } else if (field.text == "brackets" || field.text == "surroundingPairs") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        std::vector<std::string> values;
                        if (!read_string_array(values)) return nullptr;
                        auto& target = field.text == "brackets" ?
                            grammar->editor_metadata.brackets : grammar->editor_metadata.surrounding_pairs;
                        for (const auto& value : values) {
                            if (value.size() < 2) return nullptr;
                            if (value.size() != 2) return nullptr;
                            target.emplace_back(value.substr(0, 1), value.substr(1, 1));
                        }
                    } else if (field.text == "bracketNotIn") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        if (!read_string_array(grammar->editor_metadata.bracket_not_in)) return nullptr;
                    } else if (field.text == "autoClosingPairs") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                        while (true) {
                            auto pair_token = lexer.nextToken();
                            if (pair_token.type == TshLexer::TokenType::TOK_RBRACKET) break;
                            if (pair_token.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (pair_token.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                            TshAutoClosingPair pair;
                            std::unordered_map<std::string, bool> seen_pair_fields;
                            while (true) {
                                auto key = lexer.nextToken();
                                if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                if (key.type == TshLexer::TokenType::TOK_EOF ||
                                    !seen_pair_fields.emplace(key.text, true).second ||
                                    lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                if (key.text == "notIn") {
                                    if (!read_string_array(pair.not_in)) return nullptr;
                                } else if (key.text == "open" || key.text == "close") {
                                    auto value = lexer.nextToken();
                                    if ((value.type != TshLexer::TokenType::TOK_STRING &&
                                         value.type != TshLexer::TokenType::TOK_IDENT) ||
                                        value.text.empty()) return nullptr;
                                    if (key.text == "open") pair.open = value.text;
                                    else pair.close = value.text;
                                } else {
                                    return nullptr;
                                }
                            }
                            if (pair.open.empty() || pair.close.empty()) return nullptr;
                            grammar->editor_metadata.auto_closing_pairs.push_back(std::move(pair));
                        }
                    } else if (field.text == "tabSize" || field.text == "fontSize" ||
                               field.text == "lineHeight" || field.text == "letterSpacing" ||
                               field.text == "insertSpaces" || field.text == "autoIndent" ||
                               field.text == "trimTrailingWhitespace" || field.text == "formatOnType" ||
                               field.text == "formatOnPaste" || field.text == "renderWhitespace" ||
                               field.text == "lineNumbers" || field.text == "maxTokenizationLineLength") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        auto value = lexer.nextToken();
                        if (value.type != TshLexer::TokenType::TOK_IDENT &&
                            value.type != TshLexer::TokenType::TOK_NUMBER &&
                            value.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                        if (field.text == "tabSize" || field.text == "maxTokenizationLineLength") {
                            if (value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                            try {
                                size_t consumed = 0;
                                const int numeric = std::stoi(value.text, &consumed);
                                if (consumed != value.text.size() || numeric <= 0) return nullptr;
                            } catch (...) { return nullptr; }
                        } else if (field.text == "fontSize" || field.text == "lineHeight" ||
                                   field.text == "letterSpacing") {
                            if (value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                            try {
                                size_t consumed = 0;
                                const float numeric = std::stof(value.text, &consumed);
                                if (consumed != value.text.size() || !std::isfinite(numeric) ||
                                    (field.text != "letterSpacing" && numeric <= 0.0f)) return nullptr;
                            } catch (...) { return nullptr; }
                        } else if (field.text == "insertSpaces" || field.text == "autoIndent" ||
                                   field.text == "trimTrailingWhitespace" || field.text == "formatOnType" ||
                                   field.text == "formatOnPaste" || field.text == "lineNumbers") {
                            if (value.type != TshLexer::TokenType::TOK_IDENT ||
                                (value.text != "true" && value.text != "false")) return nullptr;
                        } else if (field.text == "renderWhitespace") {
                            if (value.text != "none" && value.text != "boundary" &&
                                value.text != "selection" && value.text != "all") return nullptr;
                        }
                        grammar->editor_metadata.options[field.text] = value.text;
                    } else if (field.text == "wordPattern") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        auto value = lexer.nextToken();
                        if (value.type != TshLexer::TokenType::TOK_REGEX) return nullptr;
                        grammar->editor_metadata.word_pattern = value.text;
                    } else if (field.text == "indentation") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                        while (true) {
                            auto key = lexer.nextToken();
                            if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            auto value = lexer.nextToken();
                            if (value.type != TshLexer::TokenType::TOK_REGEX) return nullptr;
                            regex_t* validation = compileOnigurumaUtf16(value.text);
                            if (!validation) return nullptr;
                            onig_free(validation);
                            if (key.text == "increaseIndentPattern") grammar->editor_metadata.increase_indent_pattern = value.text;
                            else if (key.text == "decreaseIndentPattern") grammar->editor_metadata.decrease_indent_pattern = value.text;
                            else if (key.text == "indentNextLine") grammar->editor_metadata.indent_next_line_pattern = value.text;
                            else if (key.text == "unIndentedLinePattern") grammar->editor_metadata.unindented_line_pattern = value.text;
                            else return nullptr;
                        }
                    } else if (field.text == "completion") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                        while (true) {
                            auto key = lexer.nextToken();
                            if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            if (key.text == "triggerCharacters") {
                                if (!read_string_array(grammar->editor_metadata.completion_trigger_characters)) return nullptr;
                            } else {
                                return nullptr;
                            }
                        }
                    } else if (field.text == "electricCharacters") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                            !read_string_array(grammar->editor_metadata.electric_characters)) return nullptr;
                    } else if (field.text == "autoCloseBefore") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        auto value = lexer.nextToken();
                        if (value.type != TshLexer::TokenType::TOK_REGEX) return nullptr;
                        regex_t* validation = compileOnigurumaUtf16(value.text);
                        if (!validation) return nullptr;
                        onig_free(validation);
                        grammar->editor_metadata.auto_close_before_pattern = value.text;
                    } else if (field.text == "onEnterRules") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                            lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                        while (true) {
                            auto entry = lexer.nextToken();
                            if (entry.type == TshLexer::TokenType::TOK_RBRACKET) break;
                            if (entry.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (entry.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                            TshOnEnterRule rule;
                            bool has_before = false;
                            while (true) {
                                auto key = lexer.nextToken();
                                if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                if (key.type == TshLexer::TokenType::TOK_EOF ||
                                    lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                if (key.text == "action") {
                                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                    while (true) {
                                        auto action_key = lexer.nextToken();
                                        if (action_key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                        if (action_key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                        auto action_value = lexer.nextToken();
                                        if (action_value.type != TshLexer::TokenType::TOK_STRING &&
                                            action_value.type != TshLexer::TokenType::TOK_IDENT &&
                                            action_value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                        if (action_key.text == "indent") {
                                            if (action_value.text != "none" && action_value.text != "indent" &&
                                                action_value.text != "indentOutdent" && action_value.text != "outdent") return nullptr;
                                            rule.indent_action = action_value.text;
                                        } else if (action_key.text == "appendText") {
                                            rule.append_text = action_value.text;
                                        } else if (action_key.text == "removeText") {
                                            try {
                                                size_t consumed = 0;
                                                const int value = std::stoi(action_value.text, &consumed);
                                                if (consumed != action_value.text.size() || value < 0) return nullptr;
                                                rule.remove_text = static_cast<uint32_t>(value);
                                            } catch (...) { return nullptr; }
                                        } else {
                                            return nullptr;
                                        }
                                    }
                                } else {
                                    auto value = lexer.nextToken();
                                    if (value.type != TshLexer::TokenType::TOK_REGEX) return nullptr;
                                    regex_t* validation = compileOnigurumaUtf16(value.text);
                                    if (!validation) return nullptr;
                                    onig_free(validation);
                                    if (key.text == "beforeText") { rule.before_text = value.text; has_before = true; }
                                    else if (key.text == "afterText") rule.after_text = value.text;
                                    else if (key.text == "previousLineText") rule.previous_line_text = value.text;
                                    else return nullptr;
                                }
                            }
                            if (!has_before || rule.indent_action.empty()) return nullptr;
                            grammar->editor_metadata.on_enter_rules.push_back(std::move(rule));
                        }
                    } else {
                        // Unknown editor properties are rejected rather than silently misread.
                        return nullptr;
                    }
                }
            }

            // Embedded Tree-sitter query sources. Captures are bound by
            // semantic style name and compiled as one query program.
            else if (tok.text == "treeSitter") {
                if (!mark_root_section("treeSitter")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                while (true) {
                    auto key = lexer.nextToken();
                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (key.type != TshLexer::TokenType::TOK_IDENT || key.text != "queries") return nullptr;
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                        lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                    while (true) {
                        auto entry = lexer.nextToken();
                        if (entry.type == TshLexer::TokenType::TOK_RBRACKET) break;
                        if (entry.type == TshLexer::TokenType::TOK_COMMA) continue;
                        if (entry.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                        std::string query_id;
                        std::string query_source;
                        bool enabled = true;
                        while (true) {
                            auto field = lexer.nextToken();
                            if (field.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (field.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (field.type == TshLexer::TokenType::TOK_EOF ||
                                lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            auto value = lexer.nextToken();
                            if (field.text == "id") {
                                if (value.type != TshLexer::TokenType::TOK_STRING &&
                                    value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                query_id = value.text;
                            } else if (field.text == "source") {
                                if (value.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                                query_source = value.text;
                            } else if (field.text == "enabled") {
                                if (value.text == "true") enabled = true;
                                else if (value.text == "false") enabled = false;
                                else return nullptr;
                            } else {
                                return nullptr;
                            }
                        }
                        if (query_source.empty()) return nullptr;
                        if (!query_id.empty() && !tree_sitter_query_ids.emplace(query_id, true).second) return nullptr;
                        if (enabled) dynamic_query_accumulator += query_source + "\n";
                    }
                }
            }

            // =================================================================
            // 4. 解析宏观 Tree-sitter AST 选择器: ast: [ ... ]
            // =================================================================
            else if (tok.text == "ast") {
                if (!mark_root_section("ast")) return nullptr;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                    lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                while (true) {
                    auto at = lexer.nextToken();
                    if (at.type == TshLexer::TokenType::TOK_RBRACKET) break;
                    if (at.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                    if (at.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (at.type == TshLexer::TokenType::TOK_LBRACE) {
                        AstSelectorRule ast_rule;
                        ast_rule.declaration_order = ast_declaration_order++;
                        std::string node_name, field_name, parent_name, style_bind_name;
                        auto read_ast_value = [&]() -> TshLexer::Token {
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) {
                                return {TshLexer::TokenType::TOK_EOF, ""};
                            }
                            return lexer.nextToken();
                        };

                        while (true) {
                            auto field = lexer.nextToken();
                            if (field.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (field.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                            if (field.type == TshLexer::TokenType::TOK_COMMA) continue;

                            if (field.text == "node" || field.text == "field" || field.text == "parent" ||
                                field.text == "style" || field.text == "id") {
                                auto value = read_ast_value();
                                if (value.type != TshLexer::TokenType::TOK_STRING &&
                                    value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                if (field.text == "node") node_name = value.text;
                                else if (field.text == "field") field_name = value.text;
                                else if (field.text == "parent") parent_name = value.text;
                                else if (field.text == "style") style_bind_name = value.text;
                                else ast_rule.id = value.text;
                            } else if (field.text == "query") {
                                auto value = read_ast_value();
                                if (value.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                                dynamic_query_accumulator += value.text + "\n";
                            } else if (field.text == "priority") {
                                auto value = read_ast_value();
                                if (value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                try {
                                    size_t consumed = 0;
                                    ast_rule.priority = std::stoi(value.text, &consumed);
                                    if (consumed != value.text.size()) return nullptr;
                                } catch (...) { return nullptr; }
                            } else if (field.text == "enabled") {
                                auto value = read_ast_value();
                                if (value.text == "true") ast_rule.enabled = true;
                                else if (value.text == "false") ast_rule.enabled = false;
                                else return nullptr;
                            } else if (field.text == "overlap") {
                                auto value = read_ast_value();
                                if (value.text == "allow") ast_rule.allow_overlap = true;
                                else if (value.text == "deny") ast_rule.allow_overlap = false;
                                else return nullptr;
                            } else {
                                return nullptr;
                            }
                        }

                        if (!style_bind_name.empty()) {
                            auto style_it = grammar->style_name_to_id.find(style_bind_name);
                            if (style_it == grammar->style_name_to_id.end()) {
                                const uint16_t sid = ensureStyleId(style_bind_name);
                                style_it = grammar->style_name_to_id.find(style_bind_name);
                            }
                            ast_rule.style_id = style_it->second;
                            ast_rule.has_style = true;
                        }

                        if (ts_lang) {
                            if (!node_name.empty()) {
                                ast_rule.target_symbol = ts_language_symbol_for_name(ts_lang, node_name.c_str(), static_cast<uint32_t>(node_name.length()), true);
                                if (ast_rule.target_symbol == 0) return nullptr;
                            }
                            if (!field_name.empty()) {
                                ast_rule.field_id = ts_language_field_id_for_name(ts_lang, field_name.c_str(), static_cast<uint32_t>(field_name.length()));
                                if (ast_rule.field_id == 0) return nullptr;
                            }
                            if (!parent_name.empty()) {
                                ast_rule.parent_symbol = ts_language_symbol_for_name(ts_lang, parent_name.c_str(), static_cast<uint32_t>(parent_name.length()), true);
                                if (ast_rule.parent_symbol == 0) return nullptr;
                            }
                        }

                        grammar->ast_selector_rules.push_back(ast_rule);
                    }
                }
            }

            // =================================================================
            // 5. 解析微观正则流与 Block 跨行结构: contains: [ ... ]
            // =================================================================
            else if (tok.text == "contains") {
                if (!mark_root_section("contains")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                while (true) {
                    auto rule_token = lexer.nextToken();
                    if (rule_token.type == TshLexer::TokenType::TOK_RBRACKET) break;
                    if (rule_token.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (rule_token.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                    if (rule_token.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;

                    CompiledRegexRule rule;
                    rule.declaration_order = regex_declaration_order++;
                    if (!parseRegexRule(rule)) return nullptr;
                    grammar->regex_rules.push_back(std::move(rule));
                }
            } else {
                // Root-level typos must never be silently ignored. All accepted
                // keys are parsed above; vendor extensions belong in an explicit
                // extension namespace rather than being mistaken for core syntax.
                return nullptr;
            }
        }
        tok = lexer.nextToken();
    }
    if (versioned_document) {
        versioned_document_closed = tok.type == TshLexer::TokenType::TOK_RBRACE;
        if (!versioned_document_closed || lexer.nextToken().type != TshLexer::TokenType::TOK_EOF) {
            return nullptr;
        }
    }

    if (!grammar->monarch_default_style_name.empty()) {
        grammar->monarch_default_style_id =
            ensureStyleId(grammar->monarch_default_style_name + grammar->monarch_token_postfix);
    }

    if (!grammar->monarch_states.empty()) {
        if (grammar->monarch_states.find(grammar->monarch_start_state) == grammar->monarch_states.end()) return nullptr;
        auto validate_state_reference = [&](std::string value, bool allow_stack_ops) -> bool {
            if (value.empty()) return true;
            const bool has_prefix = value.front() == '@';
            if (has_prefix) value.erase(value.begin());
            if (has_prefix && allow_stack_ops &&
                (value == "pop" || value == "popall" || value == "push")) return true;
            return grammar->monarch_states.find(value) != grammar->monarch_states.end();
        };
        std::unordered_map<std::string, uint8_t> include_visit;
        std::function<bool(const std::string&)> validate_includes = [&](const std::string& state_name) -> bool {
            uint8_t& state = include_visit[state_name];
            if (state == 2) return true;
            if (state == 1) return false;
            state = 1;
            auto rules_it = grammar->monarch_states.find(state_name);
            if (rules_it == grammar->monarch_states.end()) return false;
            for (const auto& rule : rules_it->second) {
                if (!rule.include_state.empty() && !validate_includes(rule.include_state)) return false;
            }
            state = 2;
            return true;
        };
        for (const auto& state : grammar->monarch_states) {
            if (!validate_includes(state.first)) return nullptr;
        }
        for (const auto& state : grammar->monarch_states) {
            for (const auto& rule : state.second) {
                if (!rule.include_state.empty() &&
                    grammar->monarch_states.find(rule.include_state) == grammar->monarch_states.end()) return nullptr;
                if (!validate_state_reference(rule.next_state, true) ||
                    !validate_state_reference(rule.push_state, false) ||
                    !validate_state_reference(rule.switch_state, false)) return nullptr;
                const int transitions = (!rule.next_state.empty() ? 1 : 0) +
                    (!rule.push_state.empty() ? 1 : 0) +
                    (!rule.switch_state.empty() ? 1 : 0) + (rule.pop ? 1 : 0);
                if (transitions > 1) return nullptr;
            }
        }
    }

    if (style_id_overflow) return nullptr;

    if (!grammar->default_scheme_name.empty() &&
        grammar->schemes.find(grammar->default_scheme_name) == grammar->schemes.end()) {
        return nullptr;
    }

    // Resolve scheme inheritance only after every declaration is parsed, so
    // a child scheme may extend a scheme declared later in the file.
    std::unordered_map<std::string, uint8_t> scheme_visit;
    std::function<bool(const std::string&)> resolve_scheme = [&](const std::string& name) -> bool {
        auto found = grammar->schemes.find(name);
        if (found == grammar->schemes.end()) return false;
        uint8_t& state = scheme_visit[name];
        if (state == 2) return true;
        if (state == 1) return false; // inheritance cycle
        state = 1;
        ColorScheme& child = found->second;
        if (!child.extends.empty()) {
            if (!resolve_scheme(child.extends)) return false;
            const ColorScheme parent = grammar->schemes.at(child.extends);
            auto child_colors = std::move(child.colors);
            auto child_ui = std::move(child.ui_colors);
            auto child_light_ui = std::move(child.ui_light_colors);
            auto child_dark_ui = std::move(child.ui_dark_colors);
            auto child_scope_rules = std::move(child.scope_rules);
            child.colors = parent.colors;
            for (auto& inherited : child.colors) {
                inherited.second.explicit_light_color = false;
                inherited.second.explicit_dark_color = false;
            }
            child.ui_colors = parent.ui_colors;
            child.ui_light_colors = parent.ui_light_colors;
            child.ui_dark_colors = parent.ui_dark_colors;
            child.scope_rules = parent.scope_rules;
            uint32_t next_scope_order = static_cast<uint32_t>(child.scope_rules.size());
            for (auto& scope_rule : child_scope_rules) {
                scope_rule.declaration_order = next_scope_order++;
                child.scope_rules.push_back(std::move(scope_rule));
            }
            for (const auto& entry : child_colors) {
                auto& target = child.colors[entry.first];
                if (entry.second.has_light_color) {
                    target.light_color = entry.second.light_color;
                    target.has_light_color = true;
                    target.explicit_light_color = true;
                }
                if (entry.second.has_dark_color) {
                    target.dark_color = entry.second.dark_color;
                    target.has_dark_color = true;
                    target.explicit_dark_color = true;
                }
                target.light_font_flags |= entry.second.light_font_flags;
                target.dark_font_flags |= entry.second.dark_font_flags;
            }
            for (const auto& entry : child_ui) child.ui_colors[entry.first] = entry.second;
            for (const auto& entry : child_light_ui) child.ui_light_colors[entry.first] = entry.second;
            for (const auto& entry : child_dark_ui) child.ui_dark_colors[entry.first] = entry.second;
        }
        state = 2;
        return true;
    };
    for (const auto& entry : grammar->schemes) {
        if (!resolve_scheme(entry.first)) return nullptr;
    }

    // Materialize the symbol catalog in stable ID order. Scheme colors remain
    // exclusively in ColorScheme; the style catalog contains semantic metadata only.
    grammar->styles.resize(grammar->style_name_to_id.size());
    for (const auto& entry : grammar->style_name_to_id) {
        TshStyle style;
        style.name = entry.first;
        style.style_id = entry.second;
        auto definition_it = grammar->style_definitions.find(entry.first);
        if (definition_it != grammar->style_definitions.end()) {
            style.scope = definition_it->second.scope;
            style.tags = definition_it->second.tags;
            style.description = definition_it->second.description;
            style.parent = definition_it->second.parent;
            style.deprecated = definition_it->second.deprecated;
            style.metadata = definition_it->second.metadata;
        }
        if (style.style_id >= grammar->styles.size()) {
            grammar->styles.resize(static_cast<size_t>(style.style_id) + 1);
        }
        grammar->styles[style.style_id] = std::move(style);
    }

    // Apply TextMate scope-selector theme rules to styles that do not already
    // have an explicit per-style color in the selected scheme.
    for (auto& scheme_entry : grammar->schemes) {
        auto& scheme = scheme_entry.second;
        for (const auto& style : grammar->styles) {
            const auto scope_stack = buildStyleScopeStack(*grammar, style);
            if (scope_stack.empty()) continue;
            auto& palette = scheme.colors[style.style_id];
            const bool explicit_light = palette.explicit_light_color;
            const bool explicit_dark = palette.explicit_dark_color;
            int best_light_specificity = -1;
            int best_dark_specificity = -1;
            uint32_t best_light_order = 0;
            uint32_t best_dark_order = 0;
            uint32_t best_light_color = 0;
            uint32_t best_dark_color = 0;
            for (const auto& scope_rule : scheme.scope_rules) {
                const int specificity = textMateSelectorSpecificity(scope_rule.selector, scope_stack);
                if (specificity < 0) continue;
                if (scope_rule.has_light_color && !explicit_light &&
                    (specificity > best_light_specificity ||
                     (specificity == best_light_specificity && scope_rule.declaration_order >= best_light_order))) {
                    best_light_specificity = specificity;
                    best_light_order = scope_rule.declaration_order;
                    best_light_color = scope_rule.light_color;
                }
                if (scope_rule.has_dark_color && !explicit_dark &&
                    (specificity > best_dark_specificity ||
                     (specificity == best_dark_specificity && scope_rule.declaration_order >= best_dark_order))) {
                    best_dark_specificity = specificity;
                    best_dark_order = scope_rule.declaration_order;
                    best_dark_color = scope_rule.dark_color;
                }
            }
            if (!explicit_light && best_light_specificity >= 0) {
                palette.light_color = best_light_color;
                palette.has_light_color = true;
            }
            if (!explicit_dark && best_dark_specificity >= 0) {
                palette.dark_color = best_dark_color;
                palette.has_dark_color = true;
            }
        }
    }

    // Keep extension aliases deterministic while avoiding duplicates when a
    // legacy name tuple and the explicit language block both declare one.
    {
        std::vector<std::string> unique_extensions;
        for (const auto& extension : grammar->file_extensions) {
            if (std::find(unique_extensions.begin(), unique_extensions.end(), extension) == unique_extensions.end()) {
                unique_extensions.push_back(extension);
            }
        }
        grammar->file_extensions = std::move(unique_extensions);
    }

    // Validate semantic style inheritance and resolve palette fallback by
    // declared parent style. Style names remain user-defined; no token taxonomy
    // or color defaults are embedded in the runtime.
    std::unordered_map<std::string, uint8_t> style_visit;
    std::function<bool(const std::string&)> validate_style_parent = [&](const std::string& name) -> bool {
        uint8_t& state = style_visit[name];
        if (state == 2) return true;
        if (state == 1) return false;
        state = 1;
        auto definition = grammar->style_definitions.find(name);
        if (definition != grammar->style_definitions.end() && !definition->second.parent.empty()) {
            const std::string& parent = definition->second.parent;
            if (grammar->style_name_to_id.find(parent) == grammar->style_name_to_id.end()) return false;
            if (!validate_style_parent(parent)) return false;
        }
        state = 2;
        return true;
    };
    for (const auto& entry : grammar->style_name_to_id) {
        if (!validate_style_parent(entry.first)) return nullptr;
    }

    for (auto& scheme_entry : grammar->schemes) {
        auto& palette = scheme_entry.second.colors;
        for (const auto& style_entry : grammar->style_definitions) {
            const std::string& style_name = style_entry.first;
            const std::string& parent_name = style_entry.second.parent;
            if (parent_name.empty()) continue;
            const uint16_t style_id = grammar->style_name_to_id.at(style_name);
            auto& own = palette[style_id];
            std::string ancestor = parent_name;
            std::unordered_map<std::string, bool> seen;
            while (!ancestor.empty() && seen.emplace(ancestor, true).second) {
                const uint16_t parent_id = grammar->style_name_to_id.at(ancestor);
                auto parent_color = palette.find(parent_id);
                if (parent_color != palette.end()) {
                    if (!own.has_light_color && parent_color->second.has_light_color) {
                        own.light_color = parent_color->second.light_color;
                        own.has_light_color = true;
                        own.light_font_flags |= parent_color->second.light_font_flags;
                    }
                    if (!own.has_dark_color && parent_color->second.has_dark_color) {
                        own.dark_color = parent_color->second.dark_color;
                        own.has_dark_color = true;
                        own.dark_font_flags |= parent_color->second.dark_font_flags;
                    }
                }
                auto parent_definition = grammar->style_definitions.find(ancestor);
                ancestor = parent_definition == grammar->style_definitions.end() ?
                    std::string() : parent_definition->second.parent;
            }
        }
    }

    if ((!grammar->ast_selector_rules.empty() || !dynamic_query_accumulator.empty()) && !ts_lang) {
        return nullptr;
    }

    if (ts_lang && !dynamic_query_accumulator.empty()) {
        uint32_t err_offset = 0;
        TSQueryError err_type = TSQueryErrorNone;
        grammar->dynamic_ts_query = ts_query_new(
            ts_lang,
            dynamic_query_accumulator.c_str(),
            static_cast<uint32_t>(dynamic_query_accumulator.length()),
            &err_offset,
            &err_type
        );
        if (!grammar->dynamic_ts_query) return nullptr;
        const uint32_t capture_count = ts_query_capture_count(grammar->dynamic_ts_query);
        for (uint32_t i = 0; i < capture_count; ++i) {
            uint32_t name_length = 0;
            const char* capture_name = ts_query_capture_name_for_id(
                grammar->dynamic_ts_query, static_cast<uint32_t>(i), &name_length);
            if (!capture_name || grammar->style_name_to_id.find(
                std::string(capture_name, name_length)) == grammar->style_name_to_id.end()) {
                return nullptr;
            }
        }
    }

    return grammar;
}

} // namespace windbell::tsh