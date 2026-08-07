package com.duokanbook.pi.agent;

import java.util.List;

/**
 * Input passed to a {@link StreamFn}: the system prompt, the LLM-visible message list
 * (already filtered/converted via {@code convertToLlm}), and the available tools.
 *
 * <p>Equivalent to {@code pi-ai}'s {@code Context}. {@code messages} here are
 * {@link AgentMessage}s that have passed {@code convertToLlm}, so they are guaranteed to be
 * {@code user}/{@code assistant}/{@code toolResult} (no {@link AgentMessage.CustomMessage}).
 */
public record LlmContext(String systemPrompt, List<AgentMessage> messages, List<AgentTool> tools) {
}
