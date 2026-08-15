package com.example.agent.service;

import com.example.agent.dto.AgentAskResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleAgentServicePersistenceTest {

	@Mock
	private AgentOrchestrator agentOrchestrator;

	@Mock
	private AgentLogService agentLogService;

	@InjectMocks
	private SimpleAgentService simpleAgentService;

	@Test
	void shouldReturnAgentResponseWhenLogPersistenceFails() {
		String userMessage = "为什么 SQL 查询失败？";

		AgentAskResponse response = new AgentAskResponse("请先检查表结构和字段名称。", false, null, null, null, 120L, "trace-001",
				80L, null, List.of());

		when(agentOrchestrator.execute(eq(userMessage), anyString())).thenReturn(response);

		doThrow(new RuntimeException("database unavailable")).when(agentLogService).saveRunLog(userMessage, response);

		assertThatCode(() -> {
			AgentAskResponse actual = simpleAgentService.ask(userMessage);

			assertThat(actual).isSameAs(response);
			assertThat(actual.getAnswer()).isEqualTo("请先检查表结构和字段名称。");
		}).doesNotThrowAnyException();

		verify(agentLogService).saveRunLog(userMessage, response);

		ArgumentCaptor<String> traceIdCaptor = ArgumentCaptor.forClass(String.class);

		verify(agentOrchestrator).execute(eq(userMessage), traceIdCaptor.capture());

		assertThat(traceIdCaptor.getValue()).hasSize(8);
	}

}