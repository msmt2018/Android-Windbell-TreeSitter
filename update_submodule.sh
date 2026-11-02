#!/bin/bash
# ==============================================================================
# @author android_zero <://github.com>
# Automation script for updating submodule URL, fetching commit and staging changes
# ==============================================================================

set -e

# ======================= [配置区：按需修改以下参数] =======================
MODULE_PATH="grammars/properties"
NEW_URL="https://github.com/tree-sitter-grammars/tree-sitter-properties"
TARGET_COMMIT="6310671"  # 选填：指定commit hash（如 a1b2c3d...）；留空 "" 则自动同步到新仓库默认分支最新提交
# ========================================================================

echo "--------------------------------------------------------"
echo "🚀 开始更新子模块: ${MODULE_PATH}"
echo "🔗 目标仓库 URL: ${NEW_URL}"
echo "--------------------------------------------------------"

# ========================================================================
# 1. 核心修复：更新 .gitmodules 配置并动态确保 Git 索引注册
# ========================================================================
# 写入或更新 .gitmodules 文件
git config -f .gitmodules submodule."${MODULE_PATH}".url "${NEW_URL}"

# 探测 Git 是否认识该子模块路径
if ! git submodule status "${MODULE_PATH}" >/dev/null 2>&1; then
    echo "⚠️  Git 缓存树中未找到名为 '${MODULE_PATH}' 的有效子模块引用。"
    echo "⚙️  正在执行强行重置与重新注册流程..."
    
    # 彻底清理可能存在的旧索引冲突或本地半残文件夹，防止 add 失败
    git rm --cached -f "${MODULE_PATH}" 2>/dev/null || true
    rm -rf "${MODULE_PATH}" 2>/dev/null || true
    rm -rf ".git/modules/${MODULE_PATH}" 2>/dev/null || true
    
    # 使用 force 强行建立新的子模块关联
    git submodule add --force "${NEW_URL}" "${MODULE_PATH}"
fi

# 同步并初始化本地子模块配置环境
echo "🔗 同步子模块配置与本地环境..."
git submodule sync "${MODULE_PATH}"
git submodule update --init --recursive "${MODULE_PATH}"

# ========================================================================
# 2. 进入子模块内部，强行同步 Remote 指向并抓取数据
# ========================================================================
cd "${MODULE_PATH}"

echo "🔄 强制更新子模块本地 remote origin 地址..."
git remote set-url origin "${NEW_URL}" 2>/dev/null || git remote add origin "${NEW_URL}"
echo "📥 正在从新仓库抓取全部远程分支与提交记录..."
git fetch --all --prune --tags

# ========================================================================
# 3. 决定目标 Commit
# ========================================================================
if [ -n "${TARGET_COMMIT}" ]; then
    DEST_COMMIT="${TARGET_COMMIT}"
    echo "📌 目标已指定为精确 Commit: ${DEST_COMMIT}"
else
    # 动态探测新仓库默认分支真正的 HEAD SHA
    echo "🔍 探测新仓库默认分支的最优 Commit SHA..."
    DEST_COMMIT=$(git ls-remote origin HEAD | awk '{print $1}')
    if [ -z "${DEST_COMMIT}" ]; then
        echo "❌ 错误: 无法从远程仓库获取 HEAD，请检查网络或 URL 是否正确！"
        exit 1
    fi
    echo "🌟 探测到最新 Commit SHA: ${DEST_COMMIT}"
fi

# ========================================================================
# 4. 强制切换到目标 Commit
# ========================================================================
echo "⚡ 正在强制检出（Checkout）目标 Commit..."
git checkout -f "${DEST_COMMIT}"
REAL_SHA=$(git rev-parse HEAD)

cd - > /dev/null

# ========================================================================
# 5. 将更新后的指针、.gitmodules 变动一并暂存到主项目
# ========================================================================
echo "💾 将更新后的子模块 Commit 指针与配置暂存到主项目中..."
git add .gitmodules "${MODULE_PATH}"

# ========================================================================
# 6. 自动刷新项目元数据 (如存在 generate_grammars.py)
# ========================================================================
if [ -f "generate_grammars.py" ]; then
    echo "🐍 检测到 generate_grammars.py，正在重新生成 grammars.json..."
    python3 generate_grammars.py
    if [ -f "grammars/grammars.json" ]; then
        git add grammars/grammars.json
    fi
fi

echo "--------------------------------------------------------"
echo "✅ 子模块更新成功！"
echo "📌 当前检出的真实 Commit SHA: ${REAL_SHA}"
echo ""
echo "变动已自动进入主项目暂存区，你可以直接执行以下命令提交更改："
echo "👉 git commit -m \"build: update ${MODULE_PATH} to ${NEW_URL} (${REAL_SHA:0:7})\""
echo "--------------------------------------------------------"
