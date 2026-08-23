package com.example.agent.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class SpringAiChatService {

	private static final String SYSTEM_PROMPT = """
			你是一个帮助 Java 后端学习 AI Agent 开发的助手。
			回答要清晰、具体、分步骤。
			""";

	private final ChatClient chatClient;

	public SpringAiChatService(ChatClient.Builder chatClientBuilder) {
		this.chatClient = chatClientBuilder.defaultSystem(SYSTEM_PROMPT).build();
	}

	public String chat(String userMessage) {
		return chatClient.prompt().user(userMessage).call().content();
	}

}