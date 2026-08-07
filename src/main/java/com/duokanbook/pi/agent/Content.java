package com.duokanbook.pi.agent;

/**
 * A content block inside an assistant/user/toolResult message. Sealed hierarchy so the
 * loop and proxy can pattern-match exhaustively. Mirrors {@code pi-ai}'s content union.
 *
 * <p>Discriminator is exposed via {@link #type()} for switching in Java 17 (no switch
 * type-patterns); exhaustive {@code instanceof} checks work too.
 */
public sealed interface Content permits Content.Text, Content.Image, Content.Thinking, Content.ToolCall {

	String type();

	/** Plain text content. */
	record Text(String text, String textSignature) implements Content {
		public Text(String text) {
			this(text, null);
		}

		@Override public String type() {
			return "text";
		}
	}

	/** Base64 image content (data is a base64 string; mimeType e.g. "image/png"). */
	record Image(String data, String mimeType) implements Content {
		@Override public String type() {
			return "image";
		}
	}

	/** Chain-of-thought / reasoning content. Usually hidden from providers on resubmission. */
	record Thinking(String thinking, String thinkingSignature) implements Content {
		public Thinking(String thinking) {
			this(thinking, null);
		}

		@Override public String type() {
			return "thinking";
		}
	}

	/** A tool call requested by the assistant. {@code arguments} is the parsed JSON object (a Map). */
	record ToolCall(String id, String name, Object arguments) implements Content {
		@Override public String type() {
			return "toolCall";
		}
	}
}
