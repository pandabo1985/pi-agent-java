package com.duokanbook.pi.agent;

/**
 * Provider-facing tool declaration.
 *
 * <p>This is the stable shape exposed to LLM providers. A tool has a name,
 * description and JSON schema describing its arguments.</p>
 */
public final class ToolDefinition {
    private final String name;
    private final String description;
    private final String parametersSchema;

    public ToolDefinition(String name, String description, String parametersSchema) {
        this.name = name;
        this.description = description;
        this.parametersSchema = parametersSchema;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String parametersSchema() {
        return parametersSchema;
    }
}
