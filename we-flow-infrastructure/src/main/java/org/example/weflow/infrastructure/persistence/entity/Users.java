package org.example.weflow.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class Users {

    @TableId("id")
    private String id;

    @TableField("email")
    private String email;

    @TableField("password_hash")
    private String passwordHash;

    @TableField("system_role")
    private String systemRole;

    @TableField("oauth_provider")
    private String oauthProvider;

    @TableField("oauth_id")
    private String oauthId;

    @TableField("needs_setup")
    private Boolean needsSetup;

    @TableField("token_version")
    private Integer tokenVersion;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
