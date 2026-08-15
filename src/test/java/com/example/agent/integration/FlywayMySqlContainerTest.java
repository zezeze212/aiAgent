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

		/*
		 * 当前空库应依次执行：
		 *
		 * V1：创建 Run / Step 日志表 V2：为 Step 增加工具状态和错误码
		 */
		MigrateResult firstResult = flyway.migrate();

		assertThat(firstResult.migrationsExecuted).as("第一次启动应执行 V1 和 V2").isEqualTo(2);

		Set<String> tableNames = queryTableNames();

		assertThat(tableNames).contains("agent_run_log", "agent_step_log", "flyway_schema_history");

		assertThat(countSuccessfulMigration("1")).as("V1 应且只能存在一条成功记录").isEqualTo(1);

		assertThat(countSuccessfulMigration("2")).as("V2 应且只能存在一条成功记录").isEqualTo(1);

		Set<String> stepColumns = queryStepLogColumns();

		assertThat(stepColumns).contains("tool_status", "error_code");

		/*
		 * 数据库已经处于最新版本，再次 migrate 不应该重复执行 V1/V2。
		 */
		MigrateResult secondResult = flyway.migrate();

		assertThat(secondResult.migrationsExecuted).as("第二次迁移不应重复执行历史版本").isZero();

		assertThat(countSuccessfulMigration("1")).isEqualTo(1);

		assertThat(countSuccessfulMigration("2")).isEqualTo(1);
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