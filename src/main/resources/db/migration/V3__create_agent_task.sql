CREATE TABLE agent_task
(
    task_id            VARCHAR(32)  NOT NULL COMMENT '异步任务 ID',
    requested_model_id VARCHAR(64)  NULL COMMENT '用户提交的模型标识，未指定时为空',
    model_id           VARCHAR(64)  NOT NULL COMMENT '提交时解析出的实际业务模型标识',
    status             VARCHAR(16)  NOT NULL COMMENT '任务状态',
    trace_id           VARCHAR(64)  NULL COMMENT '关联的一次 Agent 执行',
    error_code         VARCHAR(64)  NULL COMMENT '任务级错误码',
    error_message      VARCHAR(256) NULL COMMENT '对外可展示的任务错误说明',
    created_time       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_time       DATETIME(3)  NULL,
    finished_time      DATETIME(3)  NULL,

    PRIMARY KEY (task_id),
    KEY idx_agent_task_status_created (status, created_time),
    KEY idx_agent_task_trace_id (trace_id),

    CONSTRAINT chk_agent_task_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'REJECTED'))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Agent 异步任务';