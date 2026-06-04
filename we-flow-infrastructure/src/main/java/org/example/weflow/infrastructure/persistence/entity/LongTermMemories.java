package org.example.weflow.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("long_term_memories")
public class LongTermMemories {

    @TableId("memory_id")
    private String memoryId;

    @TableField("scope_type")
    private String scopeType;

    @TableField("scope_id")
    private String scopeId;

    @TableField("memory_key")
    private String memoryKey;

    @TableField("content")
    private String content;

    @TableField("source_thread_id")
    private String sourceThreadId;

    @TableField("source_run_id")
    private String sourceRunId;

    @TableField("created_by_user_id")
    private String createdByUserId;

    @TableField("importance")
    private Byte importance;

    @TableField("status")
    private String status;

    @TableField("metadata_json")
    private String metadataJson;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("last_accessed_at")
    private LocalDateTime lastAccessedAt;
}
