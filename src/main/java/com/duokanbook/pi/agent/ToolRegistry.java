package com.duokanbook.pi.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry for runtime tools exposed to the model. */
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry register(AgentTool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public AgentTool find(String name) {
        return tools.get(name);
    }

    public Collection<AgentTool> all() {
        return tools.values();
    }

    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.put("parameters", tool.parameters());
            result.add(item);
        }
        return result;
    }
}
