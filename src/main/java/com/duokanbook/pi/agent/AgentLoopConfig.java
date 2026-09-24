package com.duokanbook.pi.agent;

import java.util.List;
import java.util.Map;
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
	public Double temperature;
	public Map<String, Object> samplingParams;
	public Long maxTokens;
	public String cacheRetention;
	public Map<String, String> headers;
	public Map<String, Object> metadata;
	public String transport = "auto";
	public Object thinkingBudgets;
	public Long maxRetryDelayMs;
	/** Maximum assistant turns per run; non-positive disables the guard. */
	public Integer maxTurns = 100;
	/** Maximum time allowed for one tool future; non-positive disables the timeout. */
	public Long toolTimeoutMs = 0L;
	public SimpleStreamOptions.OnPayload onPayload;
	public SimpleStreamOptions.OnResponse onResponse;

	// ── required hook ──
	/** Converts the transcript to LLM-visible messages before each model call. Required. */
	public ConvertToLlm convertToLlm;

	// ── optional hooks ──
	public TransformContext transformContext;
	public GetApiKey getApiKey;
	/**
	 * Legacy stop hook retained for source compatibility. New code should prefer {@link #finishTurn},
	 * which can request either END or CONTINUE and also runs for error/aborted turns.
	 */
	@Deprecated
	public ShouldStopAfterTurn shouldStopAfterTurn;
	public FinishTurn finishTurn;
	public PrepareRequest prepareRequest;
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

	// ───────────────────────── nested context / result classes ─────────────────────────

	/** Context passed to {@link BeforeToolCall}. */
	public static final class BeforeToolCallContext extends ValueObject {
		private final AgentMessage.AssistantMessage assistantMessage;
		private final Content.ToolCall toolCall;
		private final Object args;
		private final AgentContext context;
		public BeforeToolCallContext(AgentMessage.AssistantMessage assistantMessage, Content.ToolCall toolCall, Object args, AgentContext context) {
			this.assistantMessage = assistantMessage; this.toolCall = toolCall; this.args = args; this.context = context;
		}
		public AgentMessage.AssistantMessage assistantMessage() { return assistantMessage; }
		public Content.ToolCall toolCall() { return toolCall; }
		public Object args() { return args; }
		public AgentContext context() { return context; }
		@Override protected String[] componentNames() { return new String[] {"assistantMessage", "toolCall", "args", "context"}; }
		@Override protected Object[] componentValues() { return new Object[] {assistantMessage, toolCall, args, context}; }
	}

	/** Context passed to {@link AfterToolCall}. */
	public static final class AfterToolCallContext extends ValueObject {
		private final AgentMessage.AssistantMessage assistantMessage;
		private final Content.ToolCall toolCall;
		private final Object args;
		private final AgentToolResult<?> result;
		private final boolean isError;
		private final AgentContext context;
		public AfterToolCallContext(AgentMessage.AssistantMessage assistantMessage, Content.ToolCall toolCall, Object args,
				AgentToolResult<?> result, boolean isError, AgentContext context) {
			this.assistantMessage = assistantMessage; this.toolCall = toolCall; this.args = args; this.result = result;
			this.isError = isError; this.context = context;
		}
		public AgentMessage.AssistantMessage assistantMessage() { return assistantMessage; }
		public Content.ToolCall toolCall() { return toolCall; }
		public Object args() { return args; }
		public AgentToolResult<?> result() { return result; }
		public boolean isError() { return isError; }
		public AgentContext context() { return context; }
		@Override protected String[] componentNames() { return new String[] {"assistantMessage", "toolCall", "args", "result", "isError", "context"}; }
		@Override protected Object[] componentValues() { return new Object[] {assistantMessage, toolCall, args, result, isError, context}; }
	}

	/** Context passed to turn-finalization and next-turn hooks. */
	public static final class TurnContext extends ValueObject {
		private final AgentMessage.AssistantMessage message;
		private final List<AgentMessage.ToolResultMessage> toolResults;
		private final AgentContext context;
		private final List<AgentMessage> newMessages;
		public TurnContext(AgentMessage.AssistantMessage message, List<AgentMessage.ToolResultMessage> toolResults,
				AgentContext context, List<AgentMessage> newMessages) {
			this.message = message; this.toolResults = toolResults; this.context = context; this.newMessages = newMessages;
		}
		public AgentMessage.AssistantMessage message() { return message; }
		public List<AgentMessage.ToolResultMessage> toolResults() { return toolResults; }
		public AgentContext context() { return context; }
		public List<AgentMessage> newMessages() { return newMessages; }
		@Override protected String[] componentNames() { return new String[] {"message", "toolResults", "context", "newMessages"}; }
		@Override protected Object[] componentValues() { return new Object[] {message, toolResults, context, newMessages}; }
	}

	/** Replacement runtime state returned from {@link PrepareNextTurn}. */
	public static final class TurnUpdate extends ValueObject {
		private final AgentContext context;
		private final List<AgentMessage> messages;
		private final Model model;
		private final ThinkingLevel thinkingLevel;

		public TurnUpdate(AgentContext context, Model model, ThinkingLevel thinkingLevel) {
			this(context, null, model, thinkingLevel);
		}

		public TurnUpdate(AgentContext context, List<AgentMessage> messages, Model model, ThinkingLevel thinkingLevel) {
			this.context = context; this.messages = messages; this.model = model; this.thinkingLevel = thinkingLevel;
		}
		public AgentContext context() { return context; }
		public List<AgentMessage> messages() { return messages; }
		public Model model() { return model; }
		public ThinkingLevel thinkingLevel() { return thinkingLevel; }
		@Override protected String[] componentNames() { return new String[] {"context", "messages", "model", "thinkingLevel"}; }
		@Override protected Object[] componentValues() { return new Object[] {context, messages, model, thinkingLevel}; }
	}

	/** Runtime state visible immediately before a provider request. */
	public static final class RequestContext extends ValueObject {
		private final AgentContext context;
		private final Model model;
		private final ThinkingLevel thinkingLevel;
		public RequestContext(AgentContext context, Model model, ThinkingLevel thinkingLevel) {
			this.context = context; this.model = model; this.thinkingLevel = thinkingLevel;
		}
		public AgentContext context() { return context; }
		public Model model() { return model; }
		public ThinkingLevel thinkingLevel() { return thinkingLevel; }
		@Override protected String[] componentNames() { return new String[] {"context", "model", "thinkingLevel"}; }
		@Override protected Object[] componentValues() { return new Object[] {context, model, thinkingLevel}; }
	}

	/** Replacement runtime state for the provider request being prepared. */
	public static final class RequestUpdate extends ValueObject {
		private final AgentContext context;
		private final Model model;
		private final ThinkingLevel thinkingLevel;
		public RequestUpdate(AgentContext context, Model model, ThinkingLevel thinkingLevel) {
			this.context = context; this.model = model; this.thinkingLevel = thinkingLevel;
		}
		public AgentContext context() { return context; }
		public Model model() { return model; }
		public ThinkingLevel thinkingLevel() { return thinkingLevel; }
		@Override protected String[] componentNames() { return new String[] {"context", "model", "thinkingLevel"}; }
		@Override protected Object[] componentValues() { return new Object[] {context, model, thinkingLevel}; }
	}

	public enum TurnAction { CONTINUE, END }

	/** Decision returned by {@link FinishTurn}. Null preserves normal scheduling. */
	public static final class TurnDecision extends ValueObject {
		private final TurnAction action;
		public TurnDecision(TurnAction action) { this.action = action; }
		public TurnAction action() { return action; }
		public static TurnDecision continueRun() { return new TurnDecision(TurnAction.CONTINUE); }
		public static TurnDecision endRun() { return new TurnDecision(TurnAction.END); }
		@Override protected String[] componentNames() { return new String[] {"action"}; }
		@Override protected Object[] componentValues() { return new Object[] {action}; }
	}

	/** Result of {@link BeforeToolCall}. {@code block=true} prevents execution. */
	public static final class BeforeResult extends ValueObject {
		private final Boolean block;
		private final String reason;
		private final Boolean terminate;
		public BeforeResult(Boolean block, String reason, Boolean terminate) {
			this.block = block; this.reason = reason; this.terminate = terminate;
		}
		public Boolean block() { return block; }
		public String reason() { return reason; }
		public Boolean terminate() { return terminate; }
		@Override protected String[] componentNames() { return new String[] {"block", "reason", "terminate"}; }
		@Override protected Object[] componentValues() { return new Object[] {block, reason, terminate}; }
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
	public static final class AfterResult extends ValueObject {
		private final List<Content> content;
		private final Object details;
		private final Usage usage;
		private final Boolean isError;
		private final Boolean terminate;
		public AfterResult(List<Content> content, Object details, Usage usage, Boolean isError, Boolean terminate) {
			this.content = content; this.details = details; this.usage = usage; this.isError = isError; this.terminate = terminate;
		}
		public List<Content> content() { return content; }
		public Object details() { return details; }
		public Usage usage() { return usage; }
		public Boolean isError() { return isError; }
		public Boolean terminate() { return terminate; }
		@Override protected String[] componentNames() { return new String[] {"content", "details", "usage", "isError", "terminate"}; }
		@Override protected Object[] componentValues() { return new Object[] {content, details, usage, isError, terminate}; }
	}

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
	public interface FinishTurn {
		CompletableFuture<TurnDecision> apply(TurnContext context, AbortSignal signal);
	}

	@FunctionalInterface
	public interface PrepareRequest {
		CompletableFuture<RequestUpdate> apply(RequestContext context, AbortSignal signal);
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
