package com.example.agent.controller;

import com.example.agent.dto.RagBenchmarkResponse;
import com.example.agent.exception.BusinessException;
import com.example.agent.exception.GlobalExceptionHandler;
import com.example.agent.rag.RagBenchmarkService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RagBenchmarkControllerTest {

	@Test
	void shouldRequireExplicitBenchmarkEnablement() {
		var runner = new ApplicationContextRunner().withUserConfiguration(RagBenchmarkController.class)
			.withBean(RagBenchmarkService.class, () -> mock(RagBenchmarkService.class));
		runner.run(context -> assertThat(context).doesNotHaveBean(RagBenchmarkController.class));
		runner.withPropertyValues("agent.rag.benchmark.enabled=true")
			.run(context -> assertThat(context).hasSingleBean(RagBenchmarkController.class));
	}

	@Test
	void shouldBindStrategyAndKeepExistingBusinessErrorEnvelope() throws Exception {
		RagBenchmarkService service = mock(RagBenchmarkService.class);
		when(service.search("rule", "唯一键冲突", 5)).thenReturn(new RagBenchmarkResponse("rag-benchmark-response-v1",
				"rule", "document", "唯一键冲突", 5, 1, 0.1, List.of(), Map.of()));
		when(service.search("dense", "唯一键冲突", 5)).thenThrow(new BusinessException(503, "dense 未启用"));
		var mvc = MockMvcBuilders.standaloneSetup(new RagBenchmarkController(service))
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
		mvc.perform(get("/rag/benchmark/search").param("strategy", "rule").param("query", "唯一键冲突"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(200))
			.andExpect(jsonPath("$.data.resultUnit").value("document"))
			.andExpect(jsonPath("$.data.effectiveTopK").value(1));
		mvc.perform(get("/rag/benchmark/search").param("strategy", "dense").param("query", "唯一键冲突"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(503));
	}

}
