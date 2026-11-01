package com.example.agent.controller;

import com.example.agent.common.Result;
import com.example.agent.dto.RagBenchmarkResponse;
import com.example.agent.rag.RagBenchmarkService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 显式开启的检索评测入口；不调用生成模型，不执行工具。 */
@RestController
@RequestMapping("/rag/benchmark")
@ConditionalOnProperty(prefix = "agent.rag.benchmark", name = "enabled", havingValue = "true")
public class RagBenchmarkController {

	private final RagBenchmarkService service;

	public RagBenchmarkController(RagBenchmarkService service) {
		this.service = service;
	}

	@GetMapping("/search")
	public Result<RagBenchmarkResponse> search(@RequestParam String strategy, @RequestParam String query,
			@RequestParam(defaultValue = "5") int topK) {
		return Result.success(service.search(strategy, query, topK));
	}
}
