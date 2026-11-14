# M7-2A：Qwen3.8-27B-FP8 部署记录

## 环境
- GPU：vllm=0.30.0
- torch=2.13.0+cu130
- transformers=5.23.0
- huggingface-hub=2.4.0
- torch_cuda = 13.0
- cuda_available = True
- gpu = NVIDIA GeForce RTX 4090
- compute_capability = (8, 9)


## 模型
- 仓库：Qwen/Qwen3.8-27B-FP8
- revision：
- API 模型名：qwen3.8-27b-fp8-agent
- 上下文上限：8192
- 最大并发序列：2
- thinking：关闭

## 验证
- 模型列表：
- 普通回答：
- tool_calls 工具名与参数：
- 工具结果回填后的回答：

## 验证边界
本次完成模型服务与工具调用协议验证。
工具结果为人工构造数据。
尚未证明 Java /agent/ask 已使用该模型。