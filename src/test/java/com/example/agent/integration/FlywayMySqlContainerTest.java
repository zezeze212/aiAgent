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

    /**
     * static 容器在当前测试类执行期间只启动一次。
     *
     * Testcontainers 会：
     * 1. 创建临时 MySQL 容器；
     * 2. 随机映射宿主机端口；
     * 3. 测试结束后自动删除容器。
     */
    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4.11"))
                    .withDatabaseName("ai_agent")
                    .withUsername("ai_agent")
                    .withPassword("test_password")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_0900_ai_ci",
                            "--default-time-zone=+08:00"
                    );

    @Test
    void shouldApplyFlywayMigrationToRealMySqlOnlyOnce() throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(
                        MYSQL.getJdbcUrl(),
                        MYSQL.getUsername(),
                        MYSQL.getPassword()
                )
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();

        // 第一次迁移：当前项目只有 V1，因此应执行一条迁移。
        MigrateResult firstResult = flyway.migrate();

        assertThat(firstResult.migrationsExecuted)
                .as("第一次启动应执行 V1 迁移")
                .isEqualTo(1);

        // 验证 V1 创建的业务表及 Flyway 历史表。
        Set<String> tableNames = queryTableNames();

        assertThat(tableNames)
                .contains(
                        "agent_run_log",
                        "agent_step_log",
                        "flyway_schema_history"
                );

        // 验证 V1 在 Flyway 历史表中记录成功。
        assertThat(countSuccessfulV1Migrations())
                .as("V1 应且只能存在一条成功记录")
                .isEqualTo(1);

        // 第二次迁移：已经执行过 V1，因此不应再次执行。
        MigrateResult secondResult = flyway.migrate();

        assertThat(secondResult.migrationsExecuted)
                .as("第二次迁移不应重复执行 V1")
                .isZero();

        assertThat(countSuccessfulV1Migrations())
                .as("重复调用 migrate 后，V1 记录仍应只有一条")
                .isEqualTo(1);
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

    private int countSuccessfulV1Migrations() throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '1'
                  AND success = 1
                """;

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {

            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
    }
}