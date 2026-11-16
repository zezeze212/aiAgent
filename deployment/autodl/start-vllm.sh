#!/usr/bin/env bash
set -euo pipefail

# FlashInfer 启动时可能即时编译 CUDA 算子。
# 显式选择 CUDA 13.0，避免自动找到系统预装的 CUDA 11.8。
export CUDA_HOME="/root/autodl-tmp/cuda-13.0"
export PATH="${CUDA_HOME}/bin:${PATH}"

if [[ ! -x "${CUDA_HOME}/bin/nvcc" ]]; then
  echo "CUDA 编译器不存在：${CUDA_HOME}/bin/nvcc" >&2
  exit 1
fi

# Conda 安装的 CUDA 库可能位于 targets/.../lib，
# 而编译组件默认只搜索 CUDA_HOME/lib64。
# 以实际存在的 libcudart.so 为准，避免写死错误目录。
CUDA_RUNTIME_LIB_DIR=""

for candidate in \
  "${CUDA_HOME}/targets/x86_64-linux/lib" \
  "${CUDA_HOME}/lib" \
  "${CUDA_HOME}/lib64"
do
  if [[ -f "${candidate}/libcudart.so" ]]; then
    CUDA_RUNTIME_LIB_DIR="${candidate}"
    break
  fi
done

if [[ -z "${CUDA_RUNTIME_LIB_DIR}" ]]; then
  echo "未找到用于链接的 libcudart.so，停止启动。" >&2
  find "${CUDA_HOME}" -name 'libcudart.so*' -print
  exit 1
fi

# 编译链接时寻找 -lcudart。
export LIBRARY_PATH="${CUDA_RUNTIME_LIB_DIR}${LIBRARY_PATH:+:${LIBRARY_PATH}}"

# 运行时加载 CUDA 动态库；保留已有库路径。
# 不要把 stubs 目录加入运行时路径。
export LD_LIBRARY_PATH="${CUDA_RUNTIME_LIB_DIR}${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"

echo "CUDA_HOME=${CUDA_HOME}"
echo "CUDA Runtime 库目录=${CUDA_RUNTIME_LIB_DIR}"

VLLM_BIN="${VLLM_BIN:-/root/autodl-tmp/envs/agent-qwen38/bin/vllm}"
# 使用绝对路径启动 vLLM，并不会自动把其环境加入 PATH。
# FlashInfer 等组件还需要通过 PATH 查找 ninja 等子进程命令。
VLLM_ENV_BIN="$(dirname "${VLLM_BIN}")"
export PATH="${VLLM_ENV_BIN}:${PATH}"

# 提前检查，避免加载完模型后才发现构建工具不可用。
if ! command -v ninja >/dev/null 2>&1; then
  echo "找不到 ninja，请在 agent-qwen38 环境中安装 ninja。" >&2
  exit 1
fi

MODEL_DIR="${MODEL_DIR:-/root/autodl-tmp/models/Qwen3.8-27B-FP8}"
SERVED_MODEL_NAME="qwen3.8-27b-fp8-agent"

# 密钥由运行环境注入，不能写进脚本或提交到 Git。
: "${AUTODL_MODEL_API_KEY:?请先设置 AUTODL_MODEL_API_KEY}"

if [[ ! -x "${VLLM_BIN}" ]]; then
  echo "找不到可执行的 vLLM：${VLLM_BIN}" >&2
  exit 1
fi

if [[ ! -f "${MODEL_DIR}/config.json" ]]; then
  echo "模型目录缺少 config.json：${MODEL_DIR}" >&2
  exit 1
fi

# 仅监听实例本机，通过 SSH 隧道供开发机访问。
# 初始限制上下文和并发，关闭 CUDA Graph，便于完成接入验证。
# 默认关闭 thinking，避免先把推理内容兼容问题带入 Java 接入。
exec "${VLLM_BIN}" serve "${MODEL_DIR}" \
  --served-model-name "${SERVED_MODEL_NAME}" \
  --host 127.0.0.1 \
  --port 8000 \
  --api-key "${AUTODL_MODEL_API_KEY}" \
  --tensor-parallel-size 1 \
  --dtype auto \
  --max-model-len 8192 \
  --max-num-seqs 2 \
  --max-num-batched-tokens 2048 \
  --gpu-memory-utilization 0.90 \
  --enforce-eager \
  --language-model-only \
  --enable-auto-tool-choice \
  --tool-call-parser qwen3_coder \
  --reasoning-parser qwen3 \
  --default-chat-template-kwargs '{"enable_thinking":false}'