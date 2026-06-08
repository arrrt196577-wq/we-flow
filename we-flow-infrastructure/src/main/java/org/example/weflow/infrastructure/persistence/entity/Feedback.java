package org.example.weflow.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("feedback")
public class Feedback {

    @TableId("feedback_id")
    private String feedbackId;

    @TableField("run_id")
    private String runId;

    @TableField("thread_id")
    private String threadId;

    @TableField("user_id")
    private String userId;

    @TableField("message_id")
    private String messageId;

    @TableField("rating")
    private Integer rating;

    @TableField("comment")
    private String comment;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
