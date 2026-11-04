import re
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed

def parse_submodules():
    """解析 gitmodules.txt 文件，提取 path 和 url"""
    try:
        with open('gitmodules.txt', 'r', encoding='utf-8') as f:
            content = f.read()
    except FileNotFoundError:
        print("❌ 错误：未找到 gitmodules.txt 文件，请先将子模块配置文本保存为该文件。")
        return []

    paths = re.findall(r'path\s*=\s*([^\s\n]+)', content)
    urls = re.findall(r'url\s*=\s*([^\s\n]+)', content)

    if len(paths) != len(urls):
        print("❌ 错误：解析出的 path 数量和 url 数量不一致，请检查文本格式！")
        return []
    
    return list(zip(paths, urls))

def add_single_submodule(path, url):
    """全量添加单个子模块（去除了 --depth 1）"""
    print(f"🚀 开始全量下载添加: {path} ...")
    # 去除了 --depth 1 参数，确保下载完整历史和所有分支
    cmd = f"git submodule add --force {url} {path}"
    try:
        # 设置超时时间为 300 秒，防止单个大仓库无限期卡死线程
        subprocess.run(cmd, shell=True, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=30000)
        return path, True, "成功"
    except subprocess.TimeoutExpired:
        return path, False, "网络超时"
    except subprocess.CalledProcessError as e:
        error_msg = e.stderr.decode().strip() if e.stderr else "未知 Git 错误"
        return path, False, error_msg

def main():
    tasks = parse_submodules()
    if not tasks:
        return

    total = len(tasks)
    print(f"📦 成功解析到 {total} 个子模块，开始启动多线程全量克隆...")

    success_count = 0
    fail_count = 0

    # 开启 8 个并发线程同时全量克隆（可根据你的带宽和 CPU 调整，建议 4-8 之间，线程太多极易被 GitHub 阶段性限流）
    with ThreadPoolExecutor(max_workers=8) as executor:
        future_to_path = {executor.submit(add_single_submodule, path, url): path for path, url in tasks}
        
        for future in as_completed(future_to_path):
            path, is_success, msg = future.result()
            if is_success:
                success_count += 1
                print(f"✅ [{success_count + fail_count}/{total}] 成功恢复: {path}")
            else:
                fail_count += 1
                print(f"❌ [{success_count + fail_count}/{total}] 失败: {path}，原因: {msg}")

    print(f"\n🎉 批量处理结束！成功: {success_count} 个，失败: {fail_count} 个。")
    if fail_count > 0:
        print("💡 提示：失败的仓库通常是因为网络抖动或第三方源在 GitHub 上已被原作者删除（404）。")

if __name__ == "__main__":
    main()
