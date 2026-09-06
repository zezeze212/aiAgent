package com.example.agent.rag.vector;

import com.example.agent.dto.KnowledgeIndexPlanResponse.Action;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIndexPlannerTest {

	@Test
	void shouldClassifyAddedUpdatedUnchangedAndDeletedDocuments() {
		Map<String, KnowledgeIndexState.DocumentState> previous = Map.of("same.md", state("hash-a", "same-1"),
				"updated.md", state("hash-b", "old-1", "old-2"), "deleted.md", state("hash-c", "deleted-1"));

		Map<String, KnowledgeIndexState.DocumentState> current = Map.of("same.md", state("hash-a", "same-1"),
				"updated.md", state("hash-new", "new-1"), "added.md", state("hash-d", "added-1"));

		var plan = KnowledgeIndexPlanner.compare("test_collection", true, previous, current);

		assertThat(plan.addedCount()).isEqualTo(1);
		assertThat(plan.updatedCount()).isEqualTo(1);
		assertThat(plan.unchangedCount()).isEqualTo(1);
		assertThat(plan.deletedCount()).isEqualTo(1);

		var actions = plan.changes()
			.stream()
			.collect(java.util.stream.Collectors.toMap(change -> change.source(), change -> change.action()));

		assertThat(actions).containsExactlyInAnyOrderEntriesOf(Map.of("same.md", Action.UNCHANGED, "updated.md",
				Action.UPDATED, "deleted.md", Action.DELETED, "added.md", Action.ADDED));

		var updated = plan.changes()
			.stream()
			.filter(change -> change.source().equals("updated.md"))
			.findFirst()
			.orElseThrow();

		assertThat(updated.previousChunkCount()).isEqualTo(2);
		assertThat(updated.currentChunkCount()).isEqualTo(1);
	}

	@Test
	void shouldReportMissingBaselineWithoutPretendingCollectionIsEmpty() {
		var current = Map.of("existing.md", state("hash-a", "chunk-1"));

		var plan = KnowledgeIndexPlanner.compare("test_collection", false, Map.of(), current);

		assertThat(plan.baselinePresent()).isFalse();
		assertThat(plan.addedCount()).isEqualTo(1);
	}

	private KnowledgeIndexState.DocumentState state(String fingerprint, String... ids) {
		return new KnowledgeIndexState.DocumentState(fingerprint, List.of(ids));
	}

}