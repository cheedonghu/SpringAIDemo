-- 对话/消息持久化(单用户,暂不带 user_id)。手动执行或交给 Boot spring.sql.init 跑。

CREATE TABLE IF NOT EXISTS conversation
(
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    title      VARCHAR(255) NULL,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS message
(
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    role            VARCHAR(16) NOT NULL COMMENT 'user / assistant',
    content         MEDIUMTEXT  NULL,
    status          VARCHAR(16) NOT NULL COMMENT 'GENERATING / DONE / FAILED',
    sources_json    JSON        NULL,
    token_count     INT         NULL,
    created_at      DATETIME    NOT NULL,
    updated_at      DATETIME    NOT NULL,
    KEY idx_conv (conversation_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 在飞中的生成的看门狗。只装尚未结束的 assistant 生成,结束即删,所以表很小、扫描廉价。
-- 巡检(StuckGenerationReaper)据此发现"Redis 流已消失但消息仍 GENERATING"的孤儿并收尾为 FAILED。
CREATE TABLE IF NOT EXISTS watchdog
(
    message_id      VARCHAR(36) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(36) NULL,
    created_at      DATETIME    NOT NULL,
    KEY idx_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
