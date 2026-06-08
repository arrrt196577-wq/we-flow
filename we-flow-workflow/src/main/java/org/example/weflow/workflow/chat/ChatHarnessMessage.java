package org.example.weflow.workflow.chat;

import java.io.Serializable;

public record ChatHarnessMessage(String role, String content) implements Serializable {
}
