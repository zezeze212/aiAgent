package com.example.agent.model;

import com.example.agent.exception.BusinessException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.Assert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentModelRegistry {

	/**
	 * 注册项由服务端创建。 客户端不能通过请求提交 baseUrl、API Key 或任意供应商模型名。
	 */
	public record Entry(String modelId, String provider, String providerModel, boolean enabled,
			boolean toolCallingSupported, ChatClient client) {

		public Entry {
			Assert.hasText(modelId, "modelId 不能为空");
			Assert.hasText(provider, "provider 不能为空");
			Assert.hasText(providerModel, "providerModel 不能为空");

			// client 允许为空，表示注册了模型，但尚未装配可用客户端。
		}
	}

	private final String defaultModelId;

	private final Map<String, Entry> entries;

	public AgentModelRegistry(String defaultModelId, List<Entry> registrations) {
		Assert.hasText(defaultModelId, "默认 modelId 不能为空");
		Assert.notNull(registrations, "模型注册列表不能为空");

		Map<String, Entry> index = new LinkedHashMap<>();

		for (Entry entry : registrations) {
			Assert.notNull(entry, "模型注册项不能为空");

			if (index.putIfAbsent(entry.modelId(), entry) != null) {
				throw new IllegalArgumentException("重复注册 modelId：" + entry.modelId());
			}
		}

		if (!index.containsKey(defaultModelId)) {
			throw new IllegalArgumentException("默认 modelId 未注册：" + defaultModelId);
		}

		this.defaultModelId = defaultModelId;
		this.entries = Map.copyOf(index);
	}

	public Entry resolve(String requestedModelId) {
		String modelId = requestedModelId == null || requestedModelId.isBlank() ? defaultModelId
				: requestedModelId.trim();

		Entry entry = entries.get(modelId);

		if (entry == null) {
			throw new BusinessException(400, "MODEL_NOT_FOUND：未知 modelId");
		}

		if (!entry.enabled() || entry.client() == null) {
			throw new BusinessException(503, "MODEL_UNAVAILABLE：所选模型当前未启用或未装配");
		}

		// 当前注册表服务于完整诊断 Agent，要求支持原有工具链。
		if (!entry.toolCallingSupported()) {
			throw new BusinessException(400, "MODEL_CAPABILITY_UNSUPPORTED：所选模型未满足工具调用要求");
		}

		return entry;
	}

}