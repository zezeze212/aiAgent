package com.example.agent.tool.springai;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCitationCheckerTest {

	@Test
	void shouldAcceptProvidedIdsAndDeduplicateRepeatedReferences() {
		var result = KnowledgeCitationChecker.check("原因 [知识:c1]。建议 [知识:c1] 和 [知识:c2]。", Set.of("c1", "c2"));

		assertThat(result.status()).isEqualTo("VALID_IDS");
		assertThat(result.citedIds()).containsExactly("c1", "c2");
		assertThat(result.unknownIds()).isEmpty();
	}

	@Test
	void shouldIdentifyUnknownIds() {
		var result = KnowledgeCitationChecker.check("已知 [知识:c1]，未知 [知识:not-provided]。", Set.of("c1"));

		assertThat(result.status()).isEqualTo("INVALID_IDS");
		assertThat(result.unknownIds()).containsExactly("not-provided");
	}

	@Test
	void shouldRejectReferencesWhenNoKnowledgeWasProvided() {
		var result = KnowledgeCitationChecker.check("依据 [知识:invented]。", Set.of());

		assertThat(result.status()).isEqualTo("INVALID_IDS");
	}

	@Test
	void shouldDistinguishNoCitationsFromValidCitations() {
		var result = KnowledgeCitationChecker.check("需要补充具体报错信息。", Set.of("c1"));

		assertThat(result.status()).isEqualTo("NO_CITATIONS");
		assertThat(result.citedIds()).isEmpty();
	}

}