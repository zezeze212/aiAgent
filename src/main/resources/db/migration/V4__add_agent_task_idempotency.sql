ALTER TABLE agent_task
    ADD COLUMN idempotency_key VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '客户端提交幂等键，未提供时为空'
        AFTER task_id,
    ADD COLUMN request_fingerprint CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '请求内容的 SHA-256 指纹'
        AFTER idempotency_key,
    ADD UNIQUE KEY uk_agent_task_idempotency_key (idempotency_key),
    ADD CONSTRAINT chk_agent_task_idempotency_pair
        CHECK (
            (idempotency_key IS NULL AND request_fingerprint IS NULL)
            OR
            (idempotency_key IS NOT NULL AND request_fingerprint IS NOT NULL)
        );