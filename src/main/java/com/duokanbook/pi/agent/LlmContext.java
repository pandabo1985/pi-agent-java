package com.duokanbook.pi.agent;

import java.util.List;

/** Input passed to a stream function. */
public final class LlmContext extends ValueObject {
	private final String systemPrompt;
	private final List<AgentMessage> messages;
	private final List<AgentTool> tools;
	public LlmContext(String systemPrompt, List<AgentMessage> messages, List<AgentTool> tools) {
		this.systemPrompt = systemPrompt;
		this.messages = messages;
		this.tools = tools;
	}
	public String systemPrompt() { return systemPrompt; }
	public List<AgentMessage> messages() { return messages; }
	public List<AgentTool> tools() { return tools; }
	@Override protected String[] componentNames() { return new String[] {"systemPrompt", "messages", "tools"}; }
	@Override protected Object[] componentValues() { return new Object[] {systemPrompt, messages, tools}; }
}
