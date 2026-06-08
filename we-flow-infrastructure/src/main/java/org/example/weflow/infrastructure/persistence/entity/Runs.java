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

    @TableField("assistant_id")
    private String assistantId;

    @TableField("status")
    private String status;

    @TableField("model_name")
    private String modelName;

    @TableField("multitask_strategy")
    private String multitaskStrategy;

    @TableField("metadata_json")
    private String metadataJson;

    @TableField("kwargs_json")
    private String kwargsJson;

    @TableField("error")
    private String error;

    @TableField("message_count")
    private Integer messageCount;

    @TableField("first_human_message")
    private String firstHumanMessage;

    @TableField("last_ai_message")
    private String lastAiMessage;

    @TableField("total_input_tokens")
    private Integer totalInputTokens;

    @TableField("total_output_tokens")
    private Integer totalOutputTokens;

    @TableField("total_tokens")
    private Integer totalTokens;

    @TableField("llm_call_count")
    private Integer llmCallCount;

    @TableField("lead_agent_tokens")
    private Integer leadAgentTokens;

    @TableField("subagent_tokens")
    private Integer subagentTokens;

    @TableField("middleware_tokens")
    private Integer middlewareTokens;

    @TableField("follow_up_to_run_id")
    private String followUpToRunId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
