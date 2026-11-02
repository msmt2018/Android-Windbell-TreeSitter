# @author android_zero <github.com/msmt2018> [update] : Enhanced to support deep nested composite sub.paths, precise AST/symbol C-entry extraction, and strictly ignore node_modules folders.
# Used for automatically generating grammars/grammars.json, thus no manual addition or maintenance is required

import os
import re
import json

# 定义需要严格忽略/排除的黑名单目录名集合
EXCLUDED_DIR_NAMES = {
    "node_modules",
    ".git",
    ".github",
    "build",
    "target"
}

def get_gitmodules_paths():
    """Parse the .gitmodules file to extract all paths and language names registered under grammars/"""
    gitmodules_path = ".gitmodules"
    if not os.path.exists(gitmodules_path):
        print(f"Error: Not found in root directory '{gitmodules_path}' Documents！")
        return set()
    
    path_regex = re.compile(r'^\s*path\s*=\s*grammars/(.+)$', re.MULTILINE)
    
    with open(gitmodules_path, "r", encoding="utf-8") as f:
        content = f.read()
        
    return set(path_regex.findall(content))

def extract_tree_sitter_entry(parser_c_path):
    """
    通过高精度正则精准解析 parser.c 文件，寻找形如：
    TS_PUBLIC const TSLanguage *tree_sitter_yaml(void)
    或 const TSLanguage * tree_sitter_markdown_inline ( void )
    的 C 语言函数入口符号。
    """
    if not os.path.exists(parser_c_path):
        return None
    
    pattern = re.compile(r'(?:TS_PUBLIC\s+)?const\s+TSLanguage\s*\*\s*(tree_sitter_[a-zA-Z0-9_-]+)\s*\(\s*void\s*\)', re.DOTALL)
    
    try:
        with open(parser_c_path, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read()
            match = pattern.search(content)
            if match:
                return match.group(1)
    except Exception as e:
        print(f"⚠️ Warning: Failed to read {parser_c_path}: {e}")
        
    return None

def find_parser_subpaths(base_dir, current_rel_path=""):
    """
    递归向下扫描目录，寻找所有包含 src/parser.c 或 src/parser.cc 的叶子或中间子目录。
    自动忽略并排除 node_modules 等干扰目录。
    返回形如相对于 base_dir 的子路径列表，例如 ["grammars/interface", "grammars/ocaml", "grammars/type"] 或 ["tree-sitter-markdown", "tree-sitter-markdown-inline"]
    """
    results = []
    abs_current_dir = os.path.join(base_dir, current_rel_path) if current_rel_path else base_dir
    
    if not os.path.exists(abs_current_dir) or not os.path.isdir(abs_current_dir):
        return results

    # 路径安全检查：如果当前相对路径的任何一级目录属于黑名单（如 node_modules），直接跳过不探测
    if current_rel_path:
        path_segments = set(current_rel_path.replace("\\", "/").split("/"))
        if path_segments.intersection(EXCLUDED_DIR_NAMES):
            return results

    src_dir = os.path.join(abs_current_dir, "src")
    has_parser = os.path.exists(os.path.join(src_dir, "parser.c")) or os.path.exists(os.path.join(src_dir, "parser.cc"))
    
    if has_parser and current_rel_path:
        results.append(current_rel_path)

    try:
        entries = os.listdir(abs_current_dir)
    except OSError:
        return results

    for entry in sorted(entries):
        # 排除隐藏目录/文件 以及 node_modules 等黑名单目录
        if entry.startswith('.') or entry in EXCLUDED_DIR_NAMES:
            continue
            
        full_entry_path = os.path.join(abs_current_dir, entry)
        if os.path.isdir(full_entry_path):
            next_rel_path = os.path.join(current_rel_path, entry) if current_rel_path else entry
            results.extend(find_parser_subpaths(base_dir, next_rel_path))

    return results

def generate_grammars_json():
    grammars_dir = "grammars"
    output_file = os.path.join(grammars_dir, "grammars.json")
    tmp_output_file = os.path.join(grammars_dir, "grammars.json.tmp")
    
    if not os.path.exists(grammars_dir):
        print(f"Error: '{grammars_dir}' folder not found!")
        return

    git_languages = get_gitmodules_paths()
    if not git_languages:
        print("Tip: No grammars/ paths are registered in .gitmodules.")
        return

    local_dirs = set([d for d in os.listdir(grammars_dir) 
                      if os.path.isdir(os.path.join(grammars_dir, d)) and not d.startswith('.') and d not in EXCLUDED_DIR_NAMES])

    valid_languages = sorted(list(git_languages.intersection(local_dirs)))

    new_grammars_list = []
    for lang_name in valid_languages:
        lang_full_path = os.path.join(grammars_dir, lang_name)
        
        # 递归探测子路径，自动过滤 node_modules 等目录
        discovered_subpaths = find_parser_subpaths(lang_full_path)
        discovered_subpaths = [p.replace("\\", "/") for p in discovered_subpaths]

        # 判定是否为复合多解析器架构
        if len(discovered_subpaths) >= 2 or any("/grammars/" in p or p.count("/") >= 1 for p in discovered_subpaths):
            sub_paths = [p for p in discovered_subpaths if p != "src"]
            sub_entries = []
            
            # 精准提取每个子路径下的真实 C 函数入口符号
            for sp in sub_paths:
                p_c = os.path.join(lang_full_path, sp, "src", "parser.c")
                if not os.path.exists(p_c):
                    p_c = os.path.join(lang_full_path, sp, "src", "parser.cc")
                
                entry_name = extract_tree_sitter_entry(p_c)
                if not entry_name:
                    fallback_name = sp.split('/')[-1]
                    entry_name = f"tree_sitter_{fallback_name.replace('-', '_')}"
                sub_entries.append(entry_name)
            
            grammar_entry = {
                "name": lang_name,
                "composite": True,
                "sub.paths": sub_paths,
                "sub.entries": sub_entries,
                "src.extra": []
            }
            
            extra_scanner_c = os.path.join(lang_full_path, "scanner.c")
            extra_scanner_cc = os.path.join(lang_full_path, "scanner.cc")
            if os.path.exists(extra_scanner_c):
                grammar_entry["src.extra"].append("scanner.c")
            elif os.path.exists(extra_scanner_cc):
                grammar_entry["src.extra"].append("scanner.cc")
                
            new_grammars_list.append(grammar_entry)
            print(f"🧩 Detected Composite Grammar -> [{lang_name}] sub.paths: {sub_paths} | sub.entries: {sub_entries}")

        else:
            # 传统单解析器架构
            src_dir = os.path.join(lang_full_path, "src")
            p_c = os.path.join(src_dir, "parser.c")
            if not os.path.exists(p_c):
                p_c = os.path.join(src_dir, "parser.cc")
                
            entry_name = extract_tree_sitter_entry(p_c)
            if not entry_name:
                entry_name = f"tree_sitter_{lang_name.replace('-', '_')}"

            scanner_c = os.path.join(src_dir, "scanner.c")
            scanner_cc = os.path.join(src_dir, "scanner.cc")
            
            src_extra = []
            if os.path.exists(scanner_c):
                src_extra.append("src/scanner.c")
            elif os.path.exists(scanner_cc):
                src_extra.append("src/scanner.cc")

            new_grammars_list.append({
                "name": lang_name,
                "composite": False,
                "entry": entry_name,
                "src.extra": src_extra
            })
            print(f"📦 Detected Single Grammar     -> [{lang_name}] entry: {entry_name}")

    try:
        with open(tmp_output_file, "w", encoding="utf-8") as f:
            json.dump(new_grammars_list, f, indent=2, ensure_ascii=False)
        
        if os.path.exists(output_file):
            os.remove(output_file)
        os.rename(tmp_output_file, output_file)
        
        print(f"\n✨ Success! Precise entry symbols generated with exclusions applied.")
        print(f"📄 Total valid grammars: {len(new_grammars_list)} -> Synchronized to {output_file}")

    except Exception as e:
        print(f"❌ Write failure: {e}")
        if os.path.exists(tmp_output_file):
            os.remove(tmp_output_file)

if __name__ == "__main__":
    generate_grammars_json()