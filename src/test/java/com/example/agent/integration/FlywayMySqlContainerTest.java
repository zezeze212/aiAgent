package com.example.agent.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayMySqlContainerTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.11"))
		.withDatabaseName("ai_agent")
		.withUsername("ai_agent")
		.withPassword("test_password")
		.withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
				"--default-time-zone=+08:00");

	@Test
	void shouldApplyFlywayMigrationsToRealMySqlOnlyOnce() throws SQLException {
		Flyway flyway = Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.cleanDisabled(true)
			.load();

		// V1：Run/Step；V2：工具状态；V3：异步任务。
		MigrateResult firstResult = flyway.migrate();

		assertThat(firstResult.migrationsExecuted).as("第一次启动应执行 V1、V2、V3").isEqualTo(3);

		assertThat(queryTableNames()).contains("agent_run_log", "agent_step_log", "agent_task",
				"flyway_schema_history");

		for (String version : new String[] { "1", "2", "3" }) {
			assertThat(countSuccessfulMigration(version)).isEqualTo(1);
		}

		assertThat(queryStepLogColumns()).contains("tool_status", "error_code");

		MigrateResult secondResult = flyway.migrate();
		assertThat(secondResult.migrationsExecuted).isZero();

		for (String version : new String[] { "1", "2", "3" }) {
			assertThat(countSuccessfulMigration(version)).isEqualTo(1);
		}
	}

	private Set<String> queryTableNames() throws SQLException {

		String sql = """
				SELECT table_name
				FROM information_schema.tables
				WHERE table_schema = ?
				""";

		Set<String> tableNames = new HashSet<>();

		try (Connection connection = openConnection();

				PreparedStatement statement = connection.prepareStatement(sql)) {

			statement.setString(1, MYSQL.getDatabaseName());

			try (ResultSet resultSet = statement.executeQuery()) {

				while (resultSet.next()) {
					tableNames.add(resultSet.getString("table_name"));
				}
			}
		}

		return tableNames;
	}

	private Set<String> queryStepLogColumns() throws SQLException {

		String sql = """
				SELECT column_name
				FROM information_schema.columns
				WHERE table_schema = ?
				  AND table_name = 'agent_step_log'
				""";

		Set<String> columnNames = new HashSet<>();

		try (Connection connection = openConnection();

				PreparedStatement statement = connection.prepareStatement(sql)) {

			statement.setString(1, MYSQL.getDatabaseName());

			try (ResultSet resultSet = statement.executeQuery()) {

				while (resultSet.next()) {
					columnNames.add(resultSet.getString("column_name"));
				}
			}
		}

		return columnNames;
	}

	private int countSuccessfulMigration(String version) throws SQLException {

		String sql = """
				SELECT COUNT(*)
				FROM flyway_schema_history
				WHERE version = ?
				  AND success = 1
				""";

		try (Connection connection = openConnection();

				PreparedStatement statement = connection.prepareStatement(sql)) {

			statement.setString(1, version);

			try (ResultSet resultSet = statement.executeQuery()) {

				assertThat(resultSet.next()).isTrue();

				return resultSet.getInt(1);
			}
		}
	}

	private Connection openConnection() throws SQLException {

		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}

}