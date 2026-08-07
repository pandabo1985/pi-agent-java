package com.duokanbook.pi.agent;

/**
 * Streaming protocol events produced by a {@link StreamFn}, equivalent to
 * {@code pi-ai}'s {@code AssistantMessageEventStream}. Each event carries the cumulative
 * {@code partial} assistant message (a fresh immutable snapshot per event), so consumers
 * never need to mutate shared state.
 *
 * <p>Event sequence: {@code start} → (text|thinking|toolcall) start/delta/end* → {@code done|error}.
 */
public sealed interface AssistantMessageEvent
		permits AssistantMessageEvent.Start, AssistantMessageEvent.TextStart,
				AssistantMessageEvent.TextDelta, AssistantMessageEvent.TextEnd,
				AssistantMessageEvent.ThinkingStart, AssistantMessageEvent.ThinkingDelta,
				AssistantMessageEvent.ThinkingEnd, AssistantMessageEvent.ToolCallStart,
				AssistantMessageEvent.ToolCallDelta, AssistantMessageEvent.ToolCallEnd,
				AssistantMessageEvent.Done, AssistantMessageEvent.ErrorEvent {

	/** Wire discriminator ({@code "start"}, {@code "text_delta"}, {@code "done"}, ...). */
	String type();

	/** Cumulative assistant message at the moment this event was emitted. */
	AgentMessage.AssistantMessage partialMessage();

	record Start(AgentMessage.AssistantMessage partial) implements AssistantMessageEvent {
		@Override public String type() { return "start"; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return partial; }
	}

	record TextStart(int contentIndex, AgentMessage.AssistantMessage partial) implements AssistantMessageEvent {
		@Override public String type() { return "text_start"; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return partial; }
	}

	record TextDelta(int contentIndex, String delta, AgentMessage.AssistantMessage partial) implements AssistantMessageEvent {
		@Override public String type() { return "text_delta"; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return partial; }
	}

	record TextEnd(int contentIndex, String content, AgentMessage.AssistantMessage partial) implements AssistantMessageEvent {
		@Override public String type() { return "text_end"; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return partial; }
	}

	record ThinkingStart(int contentIndex, AgentMessage.AssistantMessage partial) implements AssistantMessageEvent {
		@Override public String type() { return "thinking_start"; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return partial; }
	}

	record ThinkingDelta(int contentIndex, String delta, AgentMessage.AssistantMessage partial) implements AssistantMessageEvent {
		@Override public String type() { return "thinking_delta"; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return partial; }
	}

	record ThinkingEnd(int contentIndex, String content, AgentMessage.AssistantMessage partial) implements AssistantMessageEvent {
		@Override public String type() { return "thinking_end"; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return partial; }
	}

	record ToolCallStart(int contentIndex, AgentMessage.AssistantMessage partial) implements AssistantMessageEvent {
		@Override public String type() { return "toolcall_start"; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return partial; }
	}

	record ToolCallDelta(int contentIndex, String delta, AgentMessage.AssistantMessage partial) implements AssistantMessageEvent {
		@Override public String type() { return "toolcall_delta"; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return partial; }
	}

	record ToolCallEnd(int contentIndex, AgentMessage.AssistantMessage partial) implements AssistantMessageEvent {
		@Override public String type() { return "toolcall_end"; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return partial; }
	}

	/** Terminal: streaming completed normally. {@link #partialMessage()} returns the final message. */
	record Done(StopReason reason, AgentMessage.AssistantMessage message) implements AssistantMessageEvent {
		@Override public String type() { return "done"; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return message; }
	}

	/** Terminal: streaming failed or was aborted. {@link #partialMessage()} returns the final message. */
	record ErrorEvent(StopReason reason, String errorMessage, AgentMessage.AssistantMessage message) implements AssistantMessageEvent {
		@Override public String type() { return "error"; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return message; }
	}
}
