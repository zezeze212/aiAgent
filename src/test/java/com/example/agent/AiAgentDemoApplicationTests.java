package com.example.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@ActiveProfiles("test")
@SpringBootTest
class AiAgentDemoApplicationTests {

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@Test
	void contextLoads() {
		assertNotNull(chatClientBuilder);
	}

}