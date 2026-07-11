package com.example.agent.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlErrorEvidenceExtractorTest {

    private final SqlErrorEvidenceExtractor extractor = new SqlErrorEvidenceExtractor();

    @Test
    void shouldExtractUnknownColumn() {
        String log = """
                java.sql.SQLSyntaxErrorException:
                Unknown column 't.theme_code' in 'field list'
                """;

        SqlErrorEvidence evidence = extractor.extract(log);

        assertEquals("UNKNOWN_COLUMN", evidence.getErrorType());
        assertEquals(List.of("theme_code"), evidence.getColumns());
    }

    @Test
    void shouldExtractDuplicateKey() {
        String log = """
                java.sql.SQLIntegrityConstraintViolationException:Duplicate entry '106-1' for key 'uk_match_round'
                """;

        SqlErrorEvidence evidence = extractor.extract(log);

        assertEquals("DUPLICATE_KEY", evidence.getErrorType());
        assertEquals("106-1", evidence.getDuplicateValue());
        assertEquals(List.of("uk_match_round"), evidence.getKeys());
    }

    @Test
    void shouldExtractTableNotFound() {
        String log = """
                java.sql.SQLSyntaxErrorException:
                Table 'ai_agent.game_round_result' doesn't exist
                """;

        SqlErrorEvidence evidence = extractor.extract(log);

        assertEquals("TABLE_NOT_FOUND", evidence.getErrorType());
        assertEquals(List.of("game_round_result"), evidence.getTables());
    }

    @Test
    void shouldExtractDataTooLong() {
        String log = """
                Data too long for column 'camp_goal' at row 1
                """;

        SqlErrorEvidence evidence = extractor.extract(log);

        assertEquals("DATA_TOO_LONG", evidence.getErrorType());
        assertEquals(List.of("camp_goal"), evidence.getColumns());
    }

    @Test
    void shouldExtractSqlSyntax() {
        String log = """
                You have an error in your SQL syntax near 'role_style = ? WHERE id = ?' at line 1
                """;

        SqlErrorEvidence evidence = extractor.extract(log);

        assertEquals("SQL_SYNTAX", evidence.getErrorType());
        assertEquals("role_style = ? WHERE id = ?", evidence.getNearSql());
    }


    @Test
    void shouldExtractParameterNotFound() {
        String log = """
                Parameter 'tool' not found. Available parameters are [toolName]
                """;

        SqlErrorEvidence evidence = extractor.extract(log);

        assertEquals("PARAM_NOT_FOUND", evidence.getErrorType());
        assertEquals(List.of("tool"), evidence.getParameters());
    }


    @Test
    void shouldReturnUnknownForUnmatchedLog() {
        String log = """
                Communications link failure
                """;

        SqlErrorEvidence evidence = extractor.extract(log);

        assertEquals("UNKNOWN", evidence.getErrorType());
    }

}