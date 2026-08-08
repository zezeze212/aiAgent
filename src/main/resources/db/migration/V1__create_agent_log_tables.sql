CREATE TABLE agent_run_log
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id         VARCHAR(64)     NOT NULL COMMENT 'Agent 运行追踪 ID',
    user_message     TEXT            NOT NULL COMMENT '用户原始问题',
    answer           MEDIUMTEXT      NULL COMMENT 'Agent 最终回答',
    used_tool        TINYINT UNSIGNED NOT NULL COMMENT '是否调用工具：0-否，1-是',
    tool_name        VARCHAR(128)    NULL COMMENT '最后一次调用的工具名称',
    tool_result      MEDIUMTEXT      NULL COMMENT '最后一次工具调用结果',
    decision_cost_ms BIGINT UNSIGNED NULL COMMENT 'AI 决策总耗时，单位毫秒',
    tool_cost_ms     BIGINT UNSIGNED NULL COMMENT '工具调用总耗时，单位毫秒',
    summary_cost_ms  BIGINT UNSIGNED NULL COMMENT 'AI 总结耗时，单位毫秒',
    agent_cost_ms    BIGINT UNSIGNED NOT NULL COMMENT 'Agent 整体耗时，单位毫秒',
    success          TINYINT UNSIGNED NOT NULL COMMENT '是否成功：0-失败，1-成功',
    error_message    TEXT            NULL COMMENT '失败原因',
    created_time     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_run_trace_id (trace_id),
    KEY idx_agent_run_created_time (created_time),
    KEY idx_agent_run_tool_created_time (tool_name, created_time),
    KEY idx_agent_run_success_created_time (success, created_time),

    CONSTRAINT chk_agent_run_used_tool
        CHECK (used_tool IN (0, 1)),
    CONSTRAINT chk_agent_run_success
        CHECK (success IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Agent 运行主日志';


CREATE TABLE agent_step_log
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id      VARCHAR(64)     NOT NULL COMMENT '所属 Agent 运行追踪 ID',
    step_name     VARCHAR(64)     NOT NULL COMMENT '步骤名称',
    description   VARCHAR(512)    NULL COMMENT '步骤说明',
    success       TINYINT UNSIGNED NOT NULL COMMENT '是否成功：0-失败，1-成功',
    cost_ms       BIGINT UNSIGNED NULL COMMENT '步骤耗时，单位毫秒',
    input_text    MEDIUMTEXT      NULL COMMENT '步骤输入',
    output_text   MEDIUMTEXT      NULL COMMENT '步骤输出',
    error_message TEXT            NULL COMMENT '失败原因',
    step_order    INT UNSIGNED    NOT NULL COMMENT '步骤在本次运行中的顺序',
    created_time  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_step_trace_order (trace_id, step_order),

    CONSTRAINT chk_agent_step_success
        CHECK (success IN (0, 1)),
    CONSTRAINT fk_agent_step_run_trace
        FOREIGN KEY (trace_id)
            REFERENCES agent_run_log (trace_id)
            ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Agent 运行步骤日志';