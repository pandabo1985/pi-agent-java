package com.duokanbook.pi.agent;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Configuration for one agent-loop invocation. Mirrors {@code AgentLoopConfig} in {@code types.ts}.
 *
 * <p>All hook fields are {@code null}-able (omitted = not called). Hooks return
 * {@link CompletableFuture}; synchronous implementations should return
 * {@link CompletableFuture#completedFuture(Object)}. A hook that throws (synchronously, or by
 * completing its future exceptionally) propagates out of the loop — equivalent to the
 * TypeScript contract that a throwing hook "interrupts the low-level agent loop without
 * producing a normal event sequence." The {@link Agent} wrapper turns such a failure into a
 * synthesized error assistant message.
 */
public final class AgentLoopConfig {

	// ── request config ──
	public Model model;
	/** Default tool-execution mode. {@code null} is treated as {@link ToolExecutionMode#PARALLEL}. */
	public ToolExecutionMode toolExecution = ToolExecutionMode.PARALLEL;
	/** Requested reasoning level; {@code null} (or {@link ThinkingLevel#OFF}) means "no reasoning". */
	public ThinkingLevel reasoning;
	public String sessionId;
	public String apiKey;
	public String transport = "auto";
	public Object thinkingBudgets;
	public Long maxRetryDelayMs;

	// ── required hook ──
	/** Converts the transcript to LLM-visible messages before each model call. Required. */
	public ConvertToLlm convertToLlm;

	// ── optional hooks ──
	public TransformContext transformContext;
	public GetApiKey getApiKey;
	public ShouldStopAfterTurn shouldStopAfterTurn;
	public PrepareNextTurn prepareNextTurn;
	public GetMessages getSteeringMessages;
	public GetMessages getFollowUpMessages;
	public BeforeToolCall beforeToolCall;
	public AfterToolCall afterToolCall;

	public AgentLoopConfig() {}

	public AgentLoopConfig(Model model, ConvertToLlm convertToLlm) {
		this.model = model;
		this.convertToLlm = convertToLlm;
	}

	// ───────────────────────── nested context / result records ─────────────────────────

	/** Context passed to {@link BeforeToolCall}. */
	public record BeforeToolCallContext(
			AgentMessage.AssistantMessage assistantMessage,
			Content.ToolCall toolCall,
			Object args,
			AgentContext context) {}

	/** Context passed to {@link AfterToolCall}. */
	public record AfterToolCallContext(
			AgentMessage.AssistantMessage assistantMessage,
			Content.ToolCall toolCall,
			Object args,
			AgentToolResult<?> result,
			boolean isError,
			AgentContext context) {}

	/** Context passed to {@link ShouldStopAfterTurn} and {@link PrepareNextTurn}. */
	public record TurnContext(
			AgentMessage.AssistantMessage message,
			List<AgentMessage.ToolResultMessage> toolResults,
			AgentContext context,
			List<AgentMessage> newMessages) {}

	/** Replacement runtime state returned from {@link PrepareNextTurn}. */
	public record TurnUpdate(AgentContext context, Model model, ThinkingLevel thinkingLevel) {}

	/** Result of {@link BeforeToolCall}. {@code block=true} prevents execution. */
	public record BeforeResult(Boolean block, String reason, Boolean terminate) {
		public boolean blocked() {
			return block != null && block;
		}

		public boolean shouldTerminate() {
			return terminate != null && terminate;
		}
	}

	/**
	 * Partial override returned from {@link AfterToolCall}. Non-null fields replace the executed
	 * tool result's corresponding field; there is no deep merge (matches TypeScript semantics).
	 */
	public record AfterResult(
			List<Content> content,
			Object details,
			Usage usage,
			Boolean isError,
			Boolean terminate) {}

	// ───────────────────────── hook functional interfaces ─────────────────────────

	@FunctionalInterface
	public interface ConvertToLlm {
		CompletableFuture<List<AgentMessage>> apply(List<AgentMessage> messages);
	}

	@FunctionalInterface
	public interface TransformContext {
		CompletableFuture<List<AgentMessage>> apply(List<AgentMessage> messages, AbortSignal signal);
	}

	@FunctionalInterface
	public interface GetApiKey {
		CompletableFuture<String> apply(String provider);
	}

	@FunctionalInterface
	public interface ShouldStopAfterTurn {
		CompletableFuture<Boolean> apply(TurnContext context);
	}

	@FunctionalInterface
	public interface PrepareNextTurn {
		CompletableFuture<TurnUpdate> apply(TurnContext context);
	}

	@FunctionalInterface
	public interface GetMessages {
		CompletableFuture<List<AgentMessage>> get();
	}

	@FunctionalInterface
	public interface BeforeToolCall {
		CompletableFuture<BeforeResult> apply(BeforeToolCallContext context, AbortSignal signal);
	}

	@FunctionalInterface
	public interface AfterToolCall {
		CompletableFuture<AfterResult> apply(AfterToolCallContext context, AbortSignal signal);
	}
}
