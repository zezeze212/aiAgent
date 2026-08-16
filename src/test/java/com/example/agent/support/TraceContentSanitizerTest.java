package com.example.agent.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContentSanitizerTest {

	private final TraceContentSanitizer sanitizer = new TraceContentSanitizer();

	@Test
	void shouldMaskSensitiveValuesInJson() {
		String content = """
				{
				  "username":"admin",
				  "password":"123456",
				  "apiKey":"sk-deepseek-test",
				  "access_token":"access-abc",
				  "refreshToken":"refresh-xyz"
				}
				""";

		String result = sanitizer.sanitize(content);

		assertThat(result).contains("\"username\":\"admin\"")
			.contains("\"password\":\"***\"")
			.contains("\"apiKey\":\"***\"")
			.contains("\"access_token\":\"***\"")
			.contains("\"refreshToken\":\"***\"");

		assertThat(result).doesNotContain("123456", "sk-deepseek-test", "access-abc", "refresh-xyz");
	}

	@Test
	void shouldMaskSensitiveValuesInConfigTextAndJdbcUrl() {
		String content = """
				DEEPSEEK_API_KEY=sk-real-key
				password=my-db-password
				jdbc:mysql://localhost:3306/test?user=root&password=jdbc-password
				""";

		String result = sanitizer.sanitize(content);

		assertThat(result).contains("DEEPSEEK_API_KEY=***").contains("password=***").contains("user=root&password=***");

		assertThat(result).doesNotContain("sk-real-key", "my-db-password", "jdbc-password");
	}

	@Test
	void shouldMaskAuthorizationHeader() {
		String content = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.secret";

		String result = sanitizer.sanitize(content);

		assertThat(result).isEqualTo("Authorization: Bearer ***");
	}

	@Test
	void shouldTruncateOversizedTraceContent() {
		String content = "x".repeat(20_100);

		String result = sanitizer.sanitize(content);

		assertThat(result).hasSize(20_000 + "\n...[TRUNCATED]".length());

		assertThat(result).endsWith("...[TRUNCATED]");
	}

	@Test
	void shouldKeepNormalDiagnosticEvidenceUnchanged() {
		String content = """
				java.lang.NullPointerException: name is null
				    at com.example.order.OrderService.createOrder(OrderService.java:87)

				Unknown column 'abc_status' in 'field list'
				""";

		String result = sanitizer.sanitize(content);

		assertThat(result).isEqualTo(content);
	}

	@Test
	void shouldKeepNullAndEmptyContentUnchanged() {
		assertThat(sanitizer.sanitize(null)).isNull();

		assertThat(sanitizer.sanitize("")).isEmpty();
	}

}