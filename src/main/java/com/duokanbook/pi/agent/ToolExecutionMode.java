package com.duokanbook.pi.agent;

/** How multiple tool calls in one assistant message are executed. See {@link AgentLoopConfig#toolExecution()}. */
public enum ToolExecutionMode {
	/** Execute tool calls one at a time. */
	SEQUENTIAL,
	/** Preflight tool calls sequentially, then run the allowed ones concurrently. */
	PARALLEL
}
