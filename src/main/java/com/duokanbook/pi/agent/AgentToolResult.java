package com.duokanbook.pi.agent;

import java.util.List;

/**
 * Final or partial result produced by a tool. Mirrors {@code AgentToolResult<T>} in {@code types.ts}.
 *
 * @param details         arbitrary structured payload for logs/UI (not sent to the model)
 * @param usage           tool-local usage, if any (not used for main context accounting)
 * @param addedToolNames  tools introduced by this result, available from this point onward
 * @param terminate       hint that the agent should stop after the current batch; only takes
 *                        effect when <em>every</em> finalized result in the batch sets this true
 */
public record AgentToolResult<T>(
		List<Content> content,
		T details,
		Usage usage,
		List<String> addedToolNames,
		boolean terminate) {

	/** Convenience error result with the given message text. */
	public static AgentToolResult<Object> error(String message) {
		return new AgentToolResult<>(List.of(new Content.Text(message)), null, null, null, false);
	}

	/** Text result convenience. */
	public static AgentToolResult<Object> text(String text) {
		return new AgentToolResult<>(List.of(new Content.Text(text)), null, null, null, false);
	}
}
