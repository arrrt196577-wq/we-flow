package org.example.weflow.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("run_events")
public class RunEvents {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("thread_id")
    private String threadId;

    @TableField("run_id")
    private String runId;

    @TableField("user_id")
    private String userId;

    @TableField("event_type")
    private String eventType;

    @TableField("category")
    private String category;

    @TableField("content")
    private String content;

    @TableField("event_metadata")
    private String eventMetadata;

    @TableField("seq")
    private Integer seq;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
