package com.duokanbook.pi.agent;

/**
 * A single assistant requested tool invocation.
 *
 * <p>The id is required to correlate tool results with assistant tool calls
 * in OpenAI/Anthropic style tool protocols.</p>
 */
public final class ToolCall {
    private final String id;
    private final String name;
    private final String arguments;

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String arguments() {
        return arguments;
    }
}
