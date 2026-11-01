package com.example.agent.controller;

import com.example.agent.common.Result;
import com.example.agent.rag.RagRerankBenchmarkService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 同一次召回下的 RRF / Reranker 对照入口。 */
@RestController
@RequestMapping("/rag/benchmark")
@ConditionalOnProperty(prefix = "agent.rag.benchmark", name = { "enabled", "rerank-enabled" }, havingValue = "true")
public class RagRerankBenchmarkController {

	private final RagRerankBenchmarkService service;

	public RagRerankBenchmarkController(RagRerankBenchmarkService service) {
		this.service = service;
	}

	@GetMapping("/rerank-pair")
	public Result<RagRerankBenchmarkService.PairResponse> compare(@RequestParam String query,
			@RequestParam(defaultValue = "5") int topK) {

		return Result.success(service.compare(query, topK));
	}

}