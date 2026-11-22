ALTER TABLE agent_task
    ADD COLUMN request_message MEDIUMTEXT NULL
        COMMENT '原始用户问题；历史任务允许为空'
        AFTER request_fingerprint,
    ADD COLUMN retry_of_task_id VARCHAR(32) NULL
        COMMENT '重试来源任务；普通任务为空'
        AFTER task_id,
    ADD UNIQUE KEY uk_agent_task_retry_of (retry_of_task_id);