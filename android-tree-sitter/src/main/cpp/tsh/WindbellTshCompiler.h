/*
 * Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
 * 动态 DSL 递归下降编译器
 *
 * 彻底消除任何语言的硬编码，动态解析 .tsh 的 AST 节点声明、正则流、样式多态与宏展开
 */

#pragma once

#include "WindbellTshCore.h"
#include <string>
#include <memory>
#include <unordered_map>

namespace windbell::tsh {

class TshCompiler {
public:
    static TshCompiler& getInstance() {
        static TshCompiler instance;
        return instance;
    }

    // 从 .tsh 文件源码直接编译出可执行的高可用语法对象 (若传入 ts_lang 则同时完成符号对齐)
    std::shared_ptr<TshGrammar> compile(const std::string& source, const TSLanguage* ts_lang = nullptr);

private:
    TshCompiler() = default;

    std::string expandKeywordsToRegex(const std::string& word_list);
    uint32_t parseColorHex(const std::string& hex_str);
};

} // namespace windbell::tsh