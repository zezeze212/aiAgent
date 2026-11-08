package com.example.agent.controller;

import com.example.agent.common.Result;
import com.example.agent.rag.RagQueryRewriteBenchmarkService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag/benchmark")
@ConditionalOnProperty(prefix = "agent.rag.benchmark", name = { "enabled", "rewrite-enabled" }, havingValue = "true")
public class RagQueryRewriteBenchmarkController {

	private final RagQueryRewriteBenchmarkService service;

	public RagQueryRewriteBenchmarkController(RagQueryRewriteBenchmarkService service) {
		this.service = service;
	}

	@GetMapping("/query-rewrite-pair")
	public Result<RagQueryRewriteBenchmarkService.PairResponse> compare(@RequestParam String query,
			@RequestParam(defaultValue = "5") int topK, @RequestParam(defaultValue = "false") boolean rewriteFirst) {

		return Result.success(service.compare(query, topK, rewriteFirst));
	}

}