# Database Schema

This document records the current database table structures used by the project.

## users

```sql
-- 用户
CREATE TABLE users (
    id            CHAR(36) PRIMARY KEY,
    email         VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(128),
    system_role   VARCHAR(16) NOT NULL DEFAULT 'user',
    oauth_provider VARCHAR(32),
    oauth_id      VARCHAR(128),
    needs_setup   TINYINT(1) NOT NULL DEFAULT 0,
    token_version INT NOT NULL DEFAULT 0,
    created_at    DATETIME(6) NOT NULL,
    INDEX idx_users_oauth (oauth_provider, oauth_id)
);
```

## threads_meta

```sql
-- 会话元数据（列表页、标题、状态）
CREATE TABLE threads_meta (
    thread_id     VARCHAR(64) PRIMARY KEY,
    user_id       VARCHAR(64),
    assistant_id  VARCHAR(128),
    display_name  VARCHAR(256),
    STATUS        VARCHAR(20) NOT NULL DEFAULT 'idle',
    metadata_json JSON,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    INDEX idx_threads_user (user_id),
    INDEX idx_threads_assistant (assistant_id)
);
```

## runs

```sql
-- 运行记录（列表、token 统计）
CREATE TABLE runs (
    run_id        VARCHAR(64) PRIMARY KEY,
    thread_id     VARCHAR(64) NOT NULL,
    user_id       VARCHAR(64),
    STATUS        VARCHAR(20) NOT NULL DEFAULT 'pending',
    model_name    VARCHAR(128),
    metadata_json JSON,
    total_tokens  INT NOT NULL DEFAULT 0,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    INDEX idx_runs_thread_status (thread_id, STATUS),
    INDEX idx_runs_user (user_id)
);
```

## run_events

```sql
-- 运行事件（消息、trace，可选）
CREATE TABLE run_events (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    thread_id     VARCHAR(64) NOT NULL,
    run_id        VARCHAR(64) NOT NULL,
    user_id       VARCHAR(64),
    event_type    VARCHAR(32) NOT NULL,
    category      VARCHAR(16) NOT NULL,
    content       LONGTEXT,
    event_metadata JSON,
    seq           INT NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    UNIQUE KEY uq_events_thread_seq (thread_id, seq),
    INDEX idx_events_run (thread_id, run_id, seq)
);
```

## feedback

```sql
-- 用户反馈
CREATE TABLE feedback (
    feedback_id   VARCHAR(64) PRIMARY KEY,
    run_id        VARCHAR(64) NOT NULL,
    thread_id     VARCHAR(64) NOT NULL,
    user_id       VARCHAR(64),
    rating        TINYINT NOT NULL,
    COMMENT       TEXT,
    created_at    DATETIME(6) NOT NULL,
    UNIQUE KEY uq_feedback (thread_id, run_id, user_id)
);
```

## long_term_memories

```sql
-- 长期记忆（用户级 / 工作区级）
CREATE TABLE long_term_memories (
    memory_id        VARCHAR(64) PRIMARY KEY,
    scope_type       VARCHAR(16) NOT NULL,
    scope_id         VARCHAR(64) NOT NULL,
    memory_key       VARCHAR(128),
    content          TEXT NOT NULL,
    source_thread_id VARCHAR(64),
    source_run_id    VARCHAR(64),
    created_by_user_id VARCHAR(64),
    importance       TINYINT NOT NULL DEFAULT 0,
    status           VARCHAR(16) NOT NULL DEFAULT 'active',
    metadata_json    JSON,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    last_accessed_at DATETIME(6),
    UNIQUE KEY uq_memories_scope_key (scope_type, scope_id, memory_key),
    INDEX idx_memories_scope_status (scope_type, scope_id, status, updated_at),
    INDEX idx_memories_source (source_thread_id, source_run_id),
    INDEX idx_memories_creator (created_by_user_id)
);
```
