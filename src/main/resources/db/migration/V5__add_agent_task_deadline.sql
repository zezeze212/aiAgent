ALTER TABLE agent_task
    ADD COLUMN deadline_at DATETIME(3) NULL
        COMMENT '任务整体截止时间；历史记录允许为空'
        AFTER created_time,
DROP CHECK chk_agent_task_status,
    ADD CONSTRAINT chk_agent_task_status_v5
        CHECK (
            status IN (
                'QUEUED',
                'RUNNING',
                'SUCCEEDED',
                'FAILED',
                'REJECTED',
                'TIMED_OUT'
            )
        ),
    ADD INDEX idx_agent_task_status_deadline (status, deadline_at);