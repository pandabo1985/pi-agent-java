package com.duokanbook.pi.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Context snapshot passed into the low-level agent loop. Mirrors {@code AgentContext} in {@code types.ts}.
 *
 * <p>{@link #messages} is intentionally mutable: the loop pushes prompt, partial assistant,
 * and tool-result messages into it in place during a run (equivalent to the TypeScript
 * implementation mutating {@code context.messages}). {@link #tools} may be {@code null}.
 */
public final class AgentContext {

	public final String systemPrompt;
	public final List<AgentMessage> messages;
	public final List<AgentTool> tools;

	public AgentContext(String systemPrompt, List<AgentMessage> messages, List<AgentTool> tools) {
		this.systemPrompt = systemPrompt;
		this.messages = messages;
		this.tools = tools;
	}

	/** A shallow copy whose {@link #messages} list is a new mutable list backed by the same message objects. */
	public AgentContext copy() {
		return new AgentContext(systemPrompt, new ArrayList<>(messages), tools);
	}
}
