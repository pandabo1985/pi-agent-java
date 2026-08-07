package com.duokanbook.pi.agent;

/**
 * Stream function used by the agent loop. The Java analogue of {@code StreamFn} in
 * {@code types.ts}; {@code Models.streamSimple} would satisfy this shape.
 *
 * <p><b>Contract (must be obeyed by every implementation):</b>
 * <ul>
 *   <li>Must not throw. All request/model/runtime failures must be encoded in the returned
 *       stream via a terminal {@link AssistantMessageEvent.ErrorEvent} whose
 *       {@code partialMessage()} carries a final assistant message with
 *       {@code stopReason = ABORTED or ERROR} and an {@code errorMessage}.</li>
 *   <li>Returns an {@link EventStream} immediately; the producer runs asynchronously.</li>
 * </ul>
 */
@FunctionalInterface
public interface StreamFn {

	EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> stream(
			Model model, LlmContext context, SimpleStreamOptions options);
}
