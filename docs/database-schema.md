# Database Schema

This document records the current database table structures used by the project.

## users

```sql
CREATE TABLE users (
  id VARCHAR(36) NOT NULL,
  email VARCHAR(320) NOT NULL,
  password_hash VARCHAR(128) NULL,
  system_role VARCHAR(16) NOT NULL DEFAULT 'user',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  oauth_provider VARCHAR(32) NULL,
  oauth_id VARCHAR(128) NULL,
  needs_setup TINYINT(1) NOT NULL DEFAULT 0,
  token_version INT NOT NULL DEFAULT 0,

  PRIMARY KEY (id),
  UNIQUE KEY uq_users_email (email),
  UNIQUE KEY idx_users_oauth_identity (oauth_provider, oauth_id)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## threads_meta

```sql
CREATE TABLE threads_meta (
  thread_id VARCHAR(64) NOT NULL,
  assistant_id VARCHAR(128) NULL,
  user_id VARCHAR(64) NULL,
  display_name VARCHAR(256) NULL,
  STATUS VARCHAR(20) NOT NULL DEFAULT 'idle',
  metadata_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

  PRIMARY KEY (thread_id),
  KEY ix_threads_meta_assistant_id (assistant_id),
  KEY ix_threads_meta_user_id (user_id)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## runs

```sql
CREATE TABLE runs (
  run_id VARCHAR(64) NOT NULL,
  thread_id VARCHAR(64) NOT NULL,
  assistant_id VARCHAR(128) NULL,
  user_id VARCHAR(64) NULL,
  STATUS VARCHAR(20) NOT NULL DEFAULT 'pending',
  model_name VARCHAR(128) NULL,
  multitask_strategy VARCHAR(20) NOT NULL DEFAULT 'reject',
  metadata_json JSON NOT NULL,
  kwargs_json JSON NOT NULL,
  ERROR TEXT NULL,

  message_count INT NOT NULL DEFAULT 0,
  first_human_message TEXT NULL,
  last_ai_message TEXT NULL,

  total_input_tokens INT NOT NULL DEFAULT 0,
  total_output_tokens INT NOT NULL DEFAULT 0,
  total_tokens INT NOT NULL DEFAULT 0,
  llm_call_count INT NOT NULL DEFAULT 0,
  lead_agent_tokens INT NOT NULL DEFAULT 0,
  subagent_tokens INT NOT NULL DEFAULT 0,
  middleware_tokens INT NOT NULL DEFAULT 0,

  follow_up_to_run_id VARCHAR(64) NULL,

  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

  PRIMARY KEY (run_id),
  KEY ix_runs_thread_id (thread_id),
  KEY ix_runs_user_id (user_id),
  KEY ix_runs_thread_status (thread_id, STATUS)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## run_events

```sql
CREATE TABLE run_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  thread_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NULL,
  event_type VARCHAR(32) NOT NULL,
  category VARCHAR(16) NOT NULL,
  content TEXT NOT NULL,
  event_metadata JSON NOT NULL,
  seq INT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (id),
  UNIQUE KEY uq_events_thread_seq (thread_id, seq),
  KEY ix_run_events_user_id (user_id),
  KEY ix_events_thread_cat_seq (thread_id, category, seq),
  KEY ix_events_run (thread_id, run_id, seq)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## feedback

```sql
CREATE TABLE feedback (
  feedback_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  thread_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NULL,
  message_id VARCHAR(64) NULL,
  rating INT NOT NULL,
  COMMENT TEXT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (feedback_id),
  UNIQUE KEY uq_feedback_thread_run_user (thread_id, run_id, user_id),
  KEY ix_feedback_run_id (run_id),
  KEY ix_feedback_thread_id (thread_id),
  KEY ix_feedback_user_id (user_id)
) ENGINE=INNODB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## long_term_memories

```sql
CREATE TABLE long_term_memories (
  memory_id VARCHAR(64) NOT NULL,
  scope_type VARCHAR(16) NOT NULL,
  scope_id VARCHAR(64) NOT NULL,
  memory_key VARCHAR(128) NULL,
  content TEXT NOT NULL,
  source_thread_id VARCHAR(64) NULL,
  source_run_id VARCHAR(64) NULL,
  created_by_user_id VARCHAR(64) NULL,
  importance TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  metadata_json JSON NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  last_accessed_at DATETIME(6) NULL,

  PRIMARY KEY (memory_id),
  UNIQUE KEY uq_memories_scope_key (scope_type, scope_id, memory_key),
  KEY idx_memories_scope_status (scope_type, scope_id, status, updated_at),
  KEY idx_memories_source (source_thread_id, source_run_id),
  KEY idx_memories_creator (created_by_user_id)
);
```
