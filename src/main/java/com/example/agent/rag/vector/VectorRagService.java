package com.example.agent.rag.vector;

import com.example.agent.dto.VectorKnowledgeSearchHit;
import com.example.agent.dto.VectorKnowledgeSearchResponse;
import com.example.agent.dto.VectorRagAskResponse;
import com.example.agent.dto.VectorRagCitation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 第一版 Dense Vector RAG 回答服务。
 *
 * 当前职责： 1. 使用 VectorKnowledgeService 检索相关知识； 2. 把检索结果组织成带编号的上下文； 3. 调用 DeepSeek 生成带引用的回答。
 *
 * 当前仍是 M5 独立验证链路，暂不接入正式 /agent/ask。
 */
@Service
@ConditionalOnProperty(prefix = "agent.rag.vector", name = "enabled", havingValue = "true")
public class VectorRagService {

	private static final String SYSTEM_PROMPT = """
			你是一个 Java 后端故障诊断知识助手。

			请严格遵守以下规则：

			1. 只能依据用户问题和提供的参考资料回答，不得编造参考资料中不存在的事实。
			2. 回答关键结论时，必须使用 [1]、[2] 这样的编号标明依据。
			3. 引用编号必须来自参考资料中已经提供的编号。
			4. 如果参考资料不足以回答，应明确说明知识库资料不足。
			5. 不要把参考资料中的内容描述成真实数据库、真实日志或真实运行结果。
			6. 回答应先说明原因，再给出排查建议。
			""";

	private static final String NO_KNOWLEDGE_ANSWER = "当前知识库没有检索到相关资料，无法基于知识库给出可靠回答。";

	private final VectorKnowledgeService vectorKnowledgeService;

	private final ChatClient chatClient;

	public VectorRagService(VectorKnowledgeService vectorKnowledgeService, ChatClient.Builder chatClientBuilder) {

		this.vectorKnowledgeService = vectorKnowledgeService;
		this.chatClient = chatClientBuilder.defaultSystem(SYSTEM_PROMPT).build();
	}

	public VectorRagAskResponse ask(String query, int topK) {
		long totalStartTime = System.currentTimeMillis();

		VectorKnowledgeSearchResponse searchResponse = vectorKnowledgeService.search(query, topK);

		List<VectorKnowledgeSearchHit> hits = searchResponse.hits();
		List<VectorRagCitation> citations = buildCitations(hits);

		if (hits.isEmpty()) {
			return new VectorRagAskResponse(query, NO_KNOWLEDGE_ANSWER, searchResponse.costMs(), 0,
					System.currentTimeMillis() - totalStartTime, citations);
		}

		String userPrompt = buildUserPrompt(query, hits);

		long generationStartTime = System.currentTimeMillis();

		String answer = chatClient.prompt().user(userPrompt).call().content();

		long generationCostMs = System.currentTimeMillis() - generationStartTime;

		if (answer == null || answer.isBlank()) {
			answer = "模型没有生成有效回答。";
		}

		return new VectorRagAskResponse(query, answer, searchResponse.costMs(), generationCostMs,
				System.currentTimeMillis() - totalStartTime, citations);
	}

	private String buildUserPrompt(String query, List<VectorKnowledgeSearchHit> hits) {

		StringBuilder contextBuilder = new StringBuilder();

		for (int index = 0; index < hits.size(); index++) {
			VectorKnowledgeSearchHit hit = hits.get(index);
			int referenceNumber = index + 1;

			contextBuilder.append("[")
				.append(referenceNumber)
				.append("]\n")
				.append("来源：")
				.append(hit.source())
				.append("\n标题：")
				.append(hit.title())
				.append("\n章节：")
				.append(hit.section())
				.append("\n内容：\n")
				.append(hit.content())
				.append("\n\n");
		}

		return """
				用户问题：

				%s

				参考资料：

				%s

				请根据参考资料回答，并在关键结论后标注对应引用编号。
				""".formatted(query, contextBuilder);
	}

	private List<VectorRagCitation> buildCitations(List<VectorKnowledgeSearchHit> hits) {

		List<VectorRagCitation> citations = new ArrayList<>(hits.size());

		for (int index = 0; index < hits.size(); index++) {
			VectorKnowledgeSearchHit hit = hits.get(index);

			citations
				.add(new VectorRagCitation(index + 1, hit.id(), hit.score(), hit.source(), hit.title(), hit.section()));
		}

		return List.copyOf(citations);
	}

}