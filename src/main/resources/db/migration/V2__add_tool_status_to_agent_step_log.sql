ALTER TABLE agent_step_log
    ADD COLUMN tool_status VARCHAR(32) NULL
        COMMENT '工具执行框架状态，仅 TOOL_EXECUTION 步骤使用'
        AFTER success,
    ADD COLUMN error_code VARCHAR(64) NULL
        COMMENT '工具业务错误码，例如 TABLE_NOT_FOUND'
        AFTER tool_status;