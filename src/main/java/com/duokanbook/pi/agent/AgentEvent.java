package com.duokanbook.pi.agent;

import java.util.List;

/**
 * Lifecycle events emitted by the agent loop for UI/observer updates. Mirrors the
 * {@code AgentEvent} union in {@code types.ts}. Discriminator is {@link #type()}.
 *
 * <p>Sequence for a simple prompt: {@code agent_start → turn_start →
 * message_start(user) → message_end(user) → message_start(assistant) →
 * message_update* → message_end(assistant) → turn_end → agent_end}.
 *
 * <p>{@code agent_end} is the last event of a run; the {@link Agent} does not become idle
 * until every awaited listener for it has settled.
 */
public sealed interface AgentEvent
		permits AgentEvent.AgentStart, AgentEvent.AgentEnd,
				AgentEvent.TurnStart, AgentEvent.TurnEnd,
				AgentEvent.MessageStart, AgentEvent.MessageUpdate, AgentEvent.MessageEnd,
				AgentEvent.ToolExecutionStart, AgentEvent.ToolExecutionUpdate,
				AgentEvent.ToolExecutionEnd {

	String type();

	// ── agent lifecycle ──
	record AgentStart() implements AgentEvent {
		@Override public String type() { return "agent_start"; }
	}

	record AgentEnd(List<AgentMessage> messages) implements AgentEvent {
		@Override public String type() { return "agent_end"; }
	}

	// ── turn lifecycle (one assistant response + its tool calls/results) ──
	record TurnStart() implements AgentEvent {
		@Override public String type() { return "turn_start"; }
	}

	record TurnEnd(AgentMessage message, List<AgentMessage.ToolResultMessage> toolResults) implements AgentEvent {
		@Override public String type() { return "turn_end"; }
	}

	// ── message lifecycle ──
	record MessageStart(AgentMessage message) implements AgentEvent {
		@Override public String type() { return "message_start"; }
	}

	/** Only emitted for assistant messages while streaming. */
	record MessageUpdate(AgentMessage message, AssistantMessageEvent assistantMessageEvent) implements AgentEvent {
		@Override public String type() { return "message_update"; }
	}

	record MessageEnd(AgentMessage message) implements AgentEvent {
		@Override public String type() { return "message_end"; }
	}

	// ── tool execution lifecycle ──
	record ToolExecutionStart(String toolCallId, String toolName, Object args) implements AgentEvent {
		@Override public String type() { return "tool_execution_start"; }
	}

	record ToolExecutionUpdate(String toolCallId, String toolName, Object args, Object partialResult) implements AgentEvent {
		@Override public String type() { return "tool_execution_update"; }
	}

	record ToolExecutionEnd(String toolCallId, String toolName, AgentToolResult<?> result, boolean isError) implements AgentEvent {
		@Override public String type() { return "tool_execution_end"; }
	}
}
