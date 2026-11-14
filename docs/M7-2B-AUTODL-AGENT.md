# M7-2B：AutoDL Qwen 正式 Agent 接入

## 接入配置
- 业务 modelId：autodl-qwen
- provider：autodl-vllm
- API 模型名：qwen3.8-27b-fp8-agent
- 默认模型：deepseek-main
- 查询改写、Embedding、重排保持原有配置边界。

## 自动测试
- 命令：
- 实际结果：

## 正式接口验证
- Qwen + RAG：traceId / 实际结果。
- Qwen + getTableSchema：traceId / 实际工具状态。
- 历史保存：模型执行信息、工具结果和回答是否一致。
- SSH 隧道关闭：Qwen 调用失败，无模型回退。
- AutoDL 不可达时默认 DeepSeek：实际结果。

## 验证边界
- 复用已有 Agent、Tool、RAG、引用审计及持久化链路。
- 本次验证功能接入，不比较两个模型的整体回答质量。
- enabled 是配置开关，不是实时健康探测。
- HTTP 读取超时不等于整个 Agent 的硬截止时间。