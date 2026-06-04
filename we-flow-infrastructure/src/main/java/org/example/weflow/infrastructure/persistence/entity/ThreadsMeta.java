package org.example.weflow.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("threads_meta")
public class ThreadsMeta {

    @TableId("thread_id")
    private String threadId;

    @TableField("user_id")
    private String userId;

    @TableField("assistant_id")
    private String assistantId;

    @TableField("display_name")
    private String displayName;

    @TableField("status")
    private String status;

    @TableField("metadata_json")
    private String metadataJson;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
