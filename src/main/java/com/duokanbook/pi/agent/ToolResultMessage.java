package com.duokanbook.pi.agent;

/**
 * Result returned after executing an assistant tool call.
 */
public final class ToolResultMessage {
    private final String toolCallId;
    private final String content;
    private final boolean error;

    public ToolResultMessage(String toolCallId, String content, boolean error) {
        this.toolCallId = toolCallId;
        this.content = content;
        this.error = error;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String content() {
        return content;
    }

    public boolean isError() {
        return error;
    }
}
