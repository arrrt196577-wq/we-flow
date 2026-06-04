package org.example.weflow.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runs")
public class Runs {

    @TableId("run_id")
    private String runId;

    @TableField("thread_id")
    private String threadId;

    @TableField("user_id")
    private String userId;

    @TableField("status")
    private String status;

    @TableField("model_name")
    private String modelName;

    @TableField("metadata_json")
    private String metadataJson;

    @TableField("total_tokens")
    private Integer totalTokens;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
