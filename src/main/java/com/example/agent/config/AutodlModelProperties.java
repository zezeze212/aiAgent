package com.example.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AutoDL 生成模型的独立配置。
 *
 * 不复用 spring.ai.openai 下的 DeepSeek 参数， 避免服务地址、密钥和供应商扩展参数相互影响。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent.models.autodl")
public class AutodlModelProperties {

	// 默认关闭：没有启动云实例时，仍可使用 DeepSeek。
	private boolean enabled = false;

	// 只填写服务根地址，/v1/chat/completions 由客户端追加。
	private String baseUrl = "http://127.0.0.1:18000";

	private String apiKey = "";

	private String model = "qwen3.8-27b-fp8-agent";

	private long connectTimeoutMs = 2000;

	// 单次 HTTP 请求的读取超时，不是整个 Agent 的总截止时间。
	private long readTimeoutMs = 45000;

	// 每轮模型生成的输出上限，不代表总上下文窗口。
	private int maxTokens = 1024;

}