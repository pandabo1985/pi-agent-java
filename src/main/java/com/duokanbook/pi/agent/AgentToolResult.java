package com.duokanbook.pi.agent;

import java.util.Collections;
import java.util.List;

/** Final or partial result produced by a tool. */
public final class AgentToolResult<T> extends ValueObject {
	private final List<Content> content;
	private final T details;
	private final Usage usage;
	private final List<String> addedToolNames;
	private final boolean terminate;

	public AgentToolResult(List<Content> content, T details, Usage usage, List<String> addedToolNames, boolean terminate) {
		this.content = content;
		this.details = details;
		this.usage = usage;
		this.addedToolNames = addedToolNames;
		this.terminate = terminate;
	}
	public List<Content> content() { return content; }
	public T details() { return details; }
	public Usage usage() { return usage; }
	public List<String> addedToolNames() { return addedToolNames; }
	public boolean terminate() { return terminate; }
	@Override protected String[] componentNames() { return new String[] {"content", "details", "usage", "addedToolNames", "terminate"}; }
	@Override protected Object[] componentValues() { return new Object[] {content, details, usage, addedToolNames, terminate}; }
	public static AgentToolResult<Object> error(String message) {
		return new AgentToolResult<Object>(Collections.<Content>singletonList(new Content.Text(message)), null, null, null, false);
	}
	public static AgentToolResult<Object> text(String text) {
		return new AgentToolResult<Object>(Collections.<Content>singletonList(new Content.Text(text)), null, null, null, false);
	}
}
