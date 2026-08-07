package com.duokanbook.pi.agent;

import java.util.List;
import java.util.Map;

/**
 * Union of standard LLM messages plus an extensible custom message type. Mirrors
 * {@code AgentMessage} in {@code types.ts}: {@code Message | CustomAgentMessages}.
 *
 * <p>The three standard messages are what {@code convertToLlm} keeps; {@link Custom} is
 * filtered out (or transformed) before the LLM call, but stays in the transcript so the
 * UI/logs can see it.
 *
 * <p>Extension: the TypeScript version supports declaration merging for arbitrary custom
 * types. Java's sealed hierarchy permits adding new permitted records (by editing this
 * file) for full type safety, or simply using {@link Custom} with a {@code type} string.
 */
public sealed interface AgentMessage
		permits AgentMessage.UserMessage, AgentMessage.AssistantMessage,
				AgentMessage.ToolResultMessage, AgentMessage.CustomMessage {

	/** Role discriminator: {@code "user"}, {@code "assistant"}, {@code "toolResult"}, or a custom type. */
	String role();

	/** Epoch millis the message was created/appended. */
	long timestamp();

	/** A user-authored message. Content is text and/or image blocks. */
	record UserMessage(List<Content> content, long timestamp) implements AgentMessage {
		public UserMessage(String text) {
			this(List.of(new Content.Text(text)), System.currentTimeMillis());
		}

		@Override public String role() {
			return "user";
		}
	}

	/** An assistant (model) message. May contain text, thinking, and tool-call blocks. */
	record AssistantMessage(
			List<Content> content,
			String api,
			String provider,
			String model,
			Usage usage,
			StopReason stopReason,
			String errorMessage,
			long timestamp) implements AgentMessage {

		public AssistantMessage {
			usage = usage != null ? usage : Usage.empty();
		}

		@Override public String role() {
			return "assistant";
		}
	}

	/** A tool execution result, returned to the model as the next message. */
	record ToolResultMessage(
			String toolCallId,
			String toolName,
			List<Content> content,
			Object details,
			Usage usage,
			List<String> addedToolNames,
			boolean isError,
			long timestamp) implements AgentMessage {

		@Override public String role() {
			return "toolResult";
		}
	}

	/**
	 * App-defined message that lives in the transcript but is not, by default, sent to the
	 * LLM (the default {@code convertToLlm} filters it out). Use for UI notifications,
	 * artifacts, etc.
	 */
	record CustomMessage(String type, Map<String, Object> data, long timestamp) implements AgentMessage {
		public CustomMessage(String type, Map<String, Object> data) {
			this(type, data, System.currentTimeMillis());
		}

		@Override public String role() {
			return type;
		}
	}
}
