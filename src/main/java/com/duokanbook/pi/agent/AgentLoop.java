package com.duokanbook.pi.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The low-level agent loop. A faithful Java port of {@code agent-loop.ts}: it works with
 * {@link AgentMessage}s throughout and converts to LLM-visible messages only at the model-call
 * boundary. Stateless across invocations (but mutates the passed-in {@link AgentContext#messages}
 * in place during a run — see the principles doc, §2/§4).
 *
 * <p>Threading: {@link #runAgentLoop} / {@link #runAgentLoopContinue} run synchronously on the
 * calling thread and block until the run finishes. {@link Agent} wraps them on a background
 * thread. Parallel tool execution uses a short-lived cached thread pool per batch.
 */
public final class AgentLoop {

	private AgentLoop() {}

	/** Sink the loop pushes lifecycle events to. May block until the event is fully handled. */
	@FunctionalInterface
	public interface EventSink {
		void emit(AgentEvent event) throws Exception;
	}

	// ───────────────────────── public entry points ─────────────────────────

	/**
	 * Start a loop with a new prompt. The prompts are appended to the context and events are
	 * emitted for them. Returns the list of newly produced messages (prompts + assistant + tools).
	 */
	public static List<AgentMessage> runAgentLoop(
			List<AgentMessage> prompts,
			AgentContext context,
			AgentLoopConfig config,
			EventSink emit,
			AbortSignal signal,
			StreamFn streamFn) throws Exception {

		List<AgentMessage> newMessages = new ArrayList<>(prompts);
		AgentContext currentContext = new AgentContext(
				context.systemPrompt,
				concat(context.messages, prompts),
				context.tools);

		emit.emit(new AgentEvent.AgentStart());
		emit.emit(new AgentEvent.TurnStart());
		for (AgentMessage prompt : prompts) {
			emit.emit(new AgentEvent.MessageStart(prompt));
			emit.emit(new AgentEvent.MessageEnd(prompt));
		}

		runLoop(currentContext, newMessages, config, signal, emit, streamFn != null ? streamFn : DefaultStreamFn.get());
		return newMessages;
	}

	/**
	 * Continue a loop from the current context without adding a new message. The last message
	 * must convert to a {@code user} or {@code toolResult} message via {@code convertToLlm}.
	 */
	public static List<AgentMessage> runAgentLoopContinue(
			AgentContext context,
			AgentLoopConfig config,
			EventSink emit,
			AbortSignal signal,
			StreamFn streamFn) throws Exception {

		if (context.messages.isEmpty()) {
			throw new IllegalStateException("Cannot continue: no messages in context");
		}
		if (last(context.messages).role().equals("assistant")) {
			throw new IllegalStateException("Cannot continue from message role: assistant");
		}

		List<AgentMessage> newMessages = new ArrayList<>();
		AgentContext currentContext = new AgentContext(context.systemPrompt, context.messages, context.tools);

		emit.emit(new AgentEvent.AgentStart());
		emit.emit(new AgentEvent.TurnStart());

		runLoop(currentContext, newMessages, config, signal, emit, streamFn != null ? streamFn : DefaultStreamFn.get());
		return newMessages;
	}

	/**
	 * Stream version: returns an {@link EventStream} of events whose result is the new messages.
	 * Runs the loop on a daemon thread. Equivalent to {@code agentLoop()} in {@code agent-loop.ts}.
	 */
	public static EventStream<AgentEvent, List<AgentMessage>> agentLoop(
			List<AgentMessage> prompts,
			AgentContext context,
			AgentLoopConfig config,
			AbortSignal signal,
			StreamFn streamFn) {

		EventStream<AgentEvent, List<AgentMessage>> stream = new EventStream<>();
		EventSink sink = stream::push;
		Thread t = new Thread(() -> {
			try {
				List<AgentMessage> msgs = runAgentLoop(prompts, context, config, sink, signal, streamFn);
				stream.end(msgs);
			} catch (Throwable e) {
				stream.completeExceptionally(e);
			}
		}, "pi-agent-loop");
		t.setDaemon(true);
		t.start();
		return stream;
	}

	// ───────────────────────── the main loop ─────────────────────────

	private static void runLoop(
			AgentContext initialContext,
			List<AgentMessage> newMessages,
			AgentLoopConfig config,
			AbortSignal signal,
			EventSink emit,
			StreamFn streamFunction) throws Exception {

		AgentContext currentContext = initialContext;
		boolean firstTurn = true;
		// Steering messages may have arrived while the caller was waiting to start.
		List<AgentMessage> pendingMessages = drainSteering(config);

		outer:
		while (true) {
			boolean hasMoreToolCalls = true;

			// Inner loop: consume tool calls and steering messages.
			while (hasMoreToolCalls || !pendingMessages.isEmpty()) {
				if (!firstTurn) {
					emit.emit(new AgentEvent.TurnStart());
				} else {
					firstTurn = false;
				}

				// Inject pending steering messages before the next assistant response.
				if (!pendingMessages.isEmpty()) {
					for (AgentMessage message : pendingMessages) {
						emit.emit(new AgentEvent.MessageStart(message));
						emit.emit(new AgentEvent.MessageEnd(message));
						currentContext.messages.add(message);
						newMessages.add(message);
					}
					pendingMessages = new ArrayList<>();
				}

				// Stream one assistant response.
				AgentMessage.AssistantMessage message = streamAssistantResponse(
						currentContext, config, signal, emit, streamFunction);
				newMessages.add(message);

				if (message.stopReason() == StopReason.ERROR || message.stopReason() == StopReason.ABORTED) {
					emit.emit(new AgentEvent.TurnEnd(message, List.of()));
					emit.emit(new AgentEvent.AgentEnd(newMessages));
					return;
				}

				// Execute any tool calls.
				List<Content.ToolCall> toolCalls = toolCallsOf(message);
				List<AgentMessage.ToolResultMessage> toolResults = new ArrayList<>();
				hasMoreToolCalls = false;
				if (!toolCalls.isEmpty()) {
					// A "length" stop truncates the output; every tool call may carry broken args.
					ExecutedToolCallBatch batch = message.stopReason() == StopReason.LENGTH
							? failToolCallsFromTruncatedMessage(toolCalls, emit)
							: executeToolCalls(currentContext, message, config, signal, emit);
					toolResults.addAll(batch.messages);
					hasMoreToolCalls = !batch.terminate;
					for (AgentMessage.ToolResultMessage r : toolResults) {
						currentContext.messages.add(r);
						newMessages.add(r);
					}
				}

				emit.emit(new AgentEvent.TurnEnd(message, toolResults));

				// Let the caller swap context/model/thinking for the next turn.
				AgentLoopConfig.TurnContext turnCtx = new AgentLoopConfig.TurnContext(
						message, toolResults, currentContext, newMessages);
				AgentLoopConfig.TurnUpdate upd = (config.prepareNextTurn == null) ? null
						: config.prepareNextTurn.apply(turnCtx).join();
				if (upd != null) {
					if (upd.context() != null) currentContext = upd.context();
					if (upd.model() != null) config.model = upd.model();
					if (upd.thinkingLevel() != null) {
						config.reasoning = upd.thinkingLevel() == ThinkingLevel.OFF ? null : upd.thinkingLevel();
					}
				}

				if (config.shouldStopAfterTurn != null
						&& config.shouldStopAfterTurn.apply(turnCtx).join()) {
					emit.emit(new AgentEvent.AgentEnd(newMessages));
					return;
				}

				pendingMessages = drainSteering(config);
			}

			// Agent would stop here — check for follow-up messages.
			List<AgentMessage> followUps = drainFollowUp(config);
			if (!followUps.isEmpty()) {
				pendingMessages = followUps;
				continue outer;
			}
			break;
		}

		emit.emit(new AgentEvent.AgentEnd(newMessages));
	}

	// ───────────────────────── streaming one assistant response ─────────────────────────

	private static AgentMessage.AssistantMessage streamAssistantResponse(
			AgentContext context,
			AgentLoopConfig config,
			AbortSignal signal,
			EventSink emit,
			StreamFn streamFunction) throws Exception {

		// 1) optional transform (AgentMessage[] -> AgentMessage[])
		List<AgentMessage> messages = context.messages;
		if (config.transformContext != null) {
			messages = config.transformContext.apply(messages, signal).join();
		}
		// 2) convert to LLM-visible messages (AgentMessage[] -> Message[])
		List<AgentMessage> llmMessages = config.convertToLlm.apply(messages).join();
		LlmContext llmContext = new LlmContext(context.systemPrompt, llmMessages, context.tools);

		// 3) resolve API key (may refresh short-lived tokens between turns)
		String resolvedApiKey = config.apiKey;
		if (config.getApiKey != null) {
			String dynamic = config.getApiKey.apply(config.model.provider()).join();
			if (dynamic != null) resolvedApiKey = dynamic;
		}

		SimpleStreamOptions options = SimpleStreamOptions.builder()
				.apiKey(resolvedApiKey)
				.signal(signal)
				.reasoning(config.reasoning)
				.sessionId(config.sessionId)
				.transport(config.transport)
				.thinkingBudgets(config.thinkingBudgets)
				.maxRetryDelayMs(config.maxRetryDelayMs)
				.build();

		EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> response =
				streamFunction.stream(config.model, llmContext, options);

		AgentMessage.AssistantMessage partial = null;
		boolean addedPartial = false;

		for (AssistantMessageEvent event : response) {
			switch (event.type()) {
				case "start" -> {
					partial = event.partialMessage();
					context.messages.add(partial);
					addedPartial = true;
					emit.emit(new AgentEvent.MessageStart(partial));
				}
				case "text_start", "text_delta", "text_end",
						"thinking_start", "thinking_delta", "thinking_end",
						"toolcall_start", "toolcall_delta", "toolcall_end" -> {
					if (partial != null) {
						partial = event.partialMessage();
						context.messages.set(context.messages.size() - 1, partial);
						emit.emit(new AgentEvent.MessageUpdate(partial, event));
					}
				}
				case "done", "error" -> {
					return finalizeAssistant(response, context, partial, addedPartial, emit);
				}
				default -> { /* ignore unknown */ }
			}
		}
		// Stream exhausted without an explicit done/error: finalize from result().
		return finalizeAssistant(response, context, partial, addedPartial, emit);
	}

	private static AgentMessage.AssistantMessage finalizeAssistant(
			EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> response,
			AgentContext context,
			AgentMessage.AssistantMessage partial,
			boolean addedPartial,
			EventSink emit) throws Exception {

		AgentMessage.AssistantMessage finalMessage = response.result().join();
		if (addedPartial) {
			context.messages.set(context.messages.size() - 1, finalMessage);
		} else {
			context.messages.add(finalMessage);
			emit.emit(new AgentEvent.MessageStart(finalMessage));
		}
		emit.emit(new AgentEvent.MessageEnd(finalMessage));
		return finalMessage;
	}

	// ───────────────────────── tool execution ─────────────────────────

	private record ExecutedToolCallBatch(List<AgentMessage.ToolResultMessage> messages, boolean terminate) {}

	private sealed interface Preparation permits Preparation.Prepared, Preparation.Immediate {
		record Prepared(Content.ToolCall toolCall, AgentTool tool, Object args) implements Preparation {}
		record Immediate(AgentToolResult<?> result, boolean isError) implements Preparation {}
	}

	private record ExecutedToolCallOutcome(AgentToolResult<?> result, boolean isError) {}

	private record FinalizedToolCall(Content.ToolCall toolCall, AgentToolResult<?> result, boolean isError) {}

	/** Fail every tool call in a message truncated by the output-token limit. */
	private static ExecutedToolCallBatch failToolCallsFromTruncatedMessage(
			List<Content.ToolCall> toolCalls, EventSink emit) throws Exception {

		List<AgentMessage.ToolResultMessage> messages = new ArrayList<>();
		for (Content.ToolCall toolCall : toolCalls) {
			emit.emit(new AgentEvent.ToolExecutionStart(toolCall.id(), toolCall.name(), toolCall.arguments()));
			FinalizedToolCall finalized = new FinalizedToolCall(toolCall, AgentToolResult.error(
					"Tool call \"" + toolCall.name() + "\" was not executed: the response hit the output token limit, "
							+ "so its arguments may be truncated. Re-issue the tool call with complete arguments."),
					true);
			emit.emit(new AgentEvent.ToolExecutionEnd(toolCall.id(), toolCall.name(), finalized.result, true));
			AgentMessage.ToolResultMessage msg = createToolResultMessage(finalized);
			emit.emit(new AgentEvent.MessageStart(msg));
			emit.emit(new AgentEvent.MessageEnd(msg));
			messages.add(msg);
		}
		return new ExecutedToolCallBatch(messages, false);
	}

	private static ExecutedToolCallBatch executeToolCalls(
			AgentContext currentContext,
			AgentMessage.AssistantMessage assistantMessage,
			AgentLoopConfig config,
			AbortSignal signal,
			EventSink emit) throws Exception {

		List<Content.ToolCall> toolCalls = toolCallsOf(assistantMessage);
		boolean hasSequential = false;
		for (Content.ToolCall tc : toolCalls) {
			AgentTool tool = findTool(currentContext, tc.name());
			if (tool != null && tool.executionMode() == ToolExecutionMode.SEQUENTIAL) {
				hasSequential = true;
				break;
			}
		}
		if (config.toolExecution == ToolExecutionMode.SEQUENTIAL || hasSequential) {
			return executeSequential(currentContext, assistantMessage, toolCalls, config, signal, emit);
		}
		return executeParallel(currentContext, assistantMessage, toolCalls, config, signal, emit);
	}

	private static ExecutedToolCallBatch executeSequential(
			AgentContext currentContext,
			AgentMessage.AssistantMessage assistantMessage,
			List<Content.ToolCall> toolCalls,
			AgentLoopConfig config,
			AbortSignal signal,
			EventSink emit) throws Exception {

		List<FinalizedToolCall> finalizedCalls = new ArrayList<>();
		List<AgentMessage.ToolResultMessage> messages = new ArrayList<>();

		for (Content.ToolCall toolCall : toolCalls) {
			emit.emit(new AgentEvent.ToolExecutionStart(toolCall.id(), toolCall.name(), toolCall.arguments()));

			Preparation preparation = prepareToolCall(currentContext, assistantMessage, toolCall, config, signal);
			FinalizedToolCall finalized;
			if (preparation instanceof Preparation.Immediate imm) {
				finalized = new FinalizedToolCall(toolCall, imm.result(), imm.isError());
			} else {
				Preparation.Prepared prepared = (Preparation.Prepared) preparation;
				ExecutedToolCallOutcome executed = executePrepared(prepared, signal, emit);
				finalized = finalizeExecuted(currentContext, assistantMessage, prepared, executed, config, signal);
			}

			emit.emit(new AgentEvent.ToolExecutionEnd(toolCall.id(), toolCall.name(), finalized.result, finalized.isError));
			AgentMessage.ToolResultMessage msg = createToolResultMessage(finalized);
			emit.emit(new AgentEvent.MessageStart(msg));
			emit.emit(new AgentEvent.MessageEnd(msg));
			finalizedCalls.add(finalized);
			messages.add(msg);

			if (signal != null && signal.isAborted()) break;
		}

		return new ExecutedToolCallBatch(messages, shouldTerminateBatch(finalizedCalls));
	}

	private static ExecutedToolCallBatch executeParallel(
			AgentContext currentContext,
			AgentMessage.AssistantMessage assistantMessage,
			List<Content.ToolCall> toolCalls,
			AgentLoopConfig config,
			AbortSignal signal,
			EventSink emit) throws Exception {

		// Preflight sequentially. Immediates emit tool_execution_end in source order right away;
		// prepared calls become thunks (kept in source order).
		List<Supplier<CompletableFuture<FinalizedToolCall>>> entries = new ArrayList<>();

		for (Content.ToolCall toolCall : toolCalls) {
			emit.emit(new AgentEvent.ToolExecutionStart(toolCall.id(), toolCall.name(), toolCall.arguments()));

			Preparation preparation = prepareToolCall(currentContext, assistantMessage, toolCall, config, signal);
			if (preparation instanceof Preparation.Immediate imm) {
				FinalizedToolCall finalized = new FinalizedToolCall(toolCall, imm.result(), imm.isError());
				emit.emit(new AgentEvent.ToolExecutionEnd(toolCall.id(), toolCall.name(), finalized.result, finalized.isError));
				entries.add(() -> CompletableFuture.completedFuture(finalized));
				if (signal != null && signal.isAborted()) break;
				continue;
			}

			Preparation.Prepared prepared = (Preparation.Prepared) preparation;
			entries.add(() -> {
				ExecutedToolCallOutcome executed = executePrepared(prepared, signal, emit);
				FinalizedToolCall finalized = finalizeExecuted(currentContext, assistantMessage, prepared, executed, config, signal);
				try {
					emit.emit(new AgentEvent.ToolExecutionEnd(prepared.toolCall().id(), prepared.toolCall().name(),
							finalized.result, finalized.isError));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				return CompletableFuture.completedFuture(finalized);
			});
			if (signal != null && signal.isAborted()) break;
		}

		// Run thunks concurrently; join in source order.
		ExecutorService pool = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "pi-agent-tool");
			t.setDaemon(true);
			return t;
		});
		List<CompletableFuture<FinalizedToolCall>> futures = new ArrayList<>();
		for (Supplier<CompletableFuture<FinalizedToolCall>> entry : entries) {
			futures.add(CompletableFuture.supplyAsync(() -> entry.get().join(), pool));
		}
		List<FinalizedToolCall> ordered = new ArrayList<>();
		try {
			for (CompletableFuture<FinalizedToolCall> f : futures) ordered.add(f.join());
		} finally {
			pool.shutdownNow();
		}

		// Emit toolResult messages in assistant source order.
		List<AgentMessage.ToolResultMessage> messages = new ArrayList<>();
		for (FinalizedToolCall finalized : ordered) {
			AgentMessage.ToolResultMessage msg = createToolResultMessage(finalized);
			emit.emit(new AgentEvent.MessageStart(msg));
			emit.emit(new AgentEvent.MessageEnd(msg));
			messages.add(msg);
		}

		return new ExecutedToolCallBatch(messages, shouldTerminateBatch(ordered));
	}

	private static boolean shouldTerminateBatch(List<FinalizedToolCall> calls) {
		if (calls.isEmpty()) return false;
		for (FinalizedToolCall c : calls) {
			if (!c.result.terminate()) return false;
		}
		return true;
	}

	private static Preparation prepareToolCall(
			AgentContext currentContext,
			AgentMessage.AssistantMessage assistantMessage,
			Content.ToolCall toolCall,
			AgentLoopConfig config,
			AbortSignal signal) {

		AgentTool tool = findTool(currentContext, toolCall.name());
		if (tool == null) {
			return new Preparation.Immediate(AgentToolResult.error("Tool " + toolCall.name() + " not found"), true);
		}
		try {
			Object validatedArgs = validateToolArguments(tool, toolCall);
			if (config.beforeToolCall != null) {
				AgentLoopConfig.BeforeResult before = config.beforeToolCall.apply(
						new AgentLoopConfig.BeforeToolCallContext(assistantMessage, toolCall, validatedArgs, currentContext),
						signal).join();
				if (signal != null && signal.isAborted()) {
					return new Preparation.Immediate(AgentToolResult.error("Operation aborted"), true);
				}
				if (before != null && before.blocked()) {
					AgentToolResult<Object> r = AgentToolResult.error(before.reason() != null ? before.reason() : "Tool execution was blocked");
					if (before.shouldTerminate()) {
						r = new AgentToolResult<>(r.content(), r.details(), r.usage(), r.addedToolNames(), true);
					}
					return new Preparation.Immediate(r, true);
				}
			}
			if (signal != null && signal.isAborted()) {
				return new Preparation.Immediate(AgentToolResult.error("Operation aborted"), true);
			}
			return new Preparation.Prepared(toolCall, tool, validatedArgs);
		} catch (Throwable e) {
			return new Preparation.Immediate(AgentToolResult.error(messageOf(e)), true);
		}
	}

	private static ExecutedToolCallOutcome executePrepared(
			Preparation.Prepared prepared, AbortSignal signal, EventSink emit) {

		AtomicBoolean accepting = new AtomicBoolean(true);
		String id = prepared.toolCall().id();
		String name = prepared.toolCall().name();
		Object args = prepared.toolCall().arguments();
		try {
			AgentToolResult<?> result = prepared.tool().execute(id, prepared.args(), signal, partial -> {
				if (!accepting.get()) return;
				try {
					emit.emit(new AgentEvent.ToolExecutionUpdate(id, name, args, partial));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}).join();
			return new ExecutedToolCallOutcome(result, false);
		} catch (Throwable e) {
			return new ExecutedToolCallOutcome(AgentToolResult.error(messageOf(e)), true);
		} finally {
			accepting.set(false);
		}
	}

	private static FinalizedToolCall finalizeExecuted(
			AgentContext currentContext,
			AgentMessage.AssistantMessage assistantMessage,
			Preparation.Prepared prepared,
			ExecutedToolCallOutcome executed,
			AgentLoopConfig config,
			AbortSignal signal) {

		AgentToolResult<?> result = executed.result();
		boolean isError = executed.isError();
		if (config.afterToolCall != null) {
			try {
				AgentLoopConfig.AfterResult after = config.afterToolCall.apply(
						new AgentLoopConfig.AfterToolCallContext(assistantMessage, prepared.toolCall(), prepared.args(),
								result, isError, currentContext),
						signal).join();
				if (after != null) {
					List<Content> content = after.content() != null ? after.content() : result.content();
					Object details = after.details() != null ? after.details() : result.details();
					Usage usage = after.usage() != null ? after.usage() : result.usage();
					List<String> added = result.addedToolNames();
					boolean term = after.terminate() != null ? after.terminate() : result.terminate();
					result = new AgentToolResult<>(content, details, usage, added, term);
					isError = after.isError() != null ? after.isError() : isError;
				}
			} catch (Throwable e) {
				result = AgentToolResult.error(messageOf(e));
				isError = true;
			}
		}
		return new FinalizedToolCall(prepared.toolCall(), result, isError);
	}

	private static AgentMessage.ToolResultMessage createToolResultMessage(FinalizedToolCall finalized) {
		AgentToolResult<?> r = finalized.result();
		return new AgentMessage.ToolResultMessage(
				finalized.toolCall().id(),
				finalized.toolCall().name(),
				r.content() != null ? r.content() : List.of(),
				r.details(),
				r.usage(),
				r.addedToolNames(),
				finalized.isError(),
				System.currentTimeMillis());
	}

	// ───────────────────────── validation & helpers ─────────────────────────

	/** Apply {@link AgentTool#prepareArguments(Object)} then lightly validate against the schema. */
	private static Object validateToolArguments(AgentTool tool, Content.ToolCall toolCall) {
		Object args = tool.prepareArguments(toolCall.arguments());
		Map<String, Object> schema = tool.parameters();
		if (schema != null) {
			Object type = schema.get("type");
			if (type == null || "object".equals(type)) {
				if (!(args instanceof Map<?, ?>)) {
					throw new IllegalArgumentException("Tool " + toolCall.name() + " expects an object argument");
				}
				Object required = schema.get("required");
				if (required instanceof List<?> reqList) {
					Map<?, ?> argMap = (Map<?, ?>) args;
					for (Object r : reqList) {
						if (!argMap.containsKey(r)) {
							throw new IllegalArgumentException(
									"Tool " + toolCall.name() + " missing required argument: " + r);
						}
					}
				}
			}
		}
		return args;
	}

	private static AgentTool findTool(AgentContext ctx, String name) {
		if (ctx.tools == null) return null;
		for (AgentTool t : ctx.tools) {
			if (t.name().equals(name)) return t;
		}
		return null;
	}

	private static List<Content.ToolCall> toolCallsOf(AgentMessage.AssistantMessage message) {
		List<Content.ToolCall> out = new ArrayList<>();
		for (Content c : message.content()) {
			if (c instanceof Content.ToolCall tc) out.add(tc);
		}
		return out;
	}

	private static List<AgentMessage> drainSteering(AgentLoopConfig config) {
		if (config.getSteeringMessages == null) return new ArrayList<>();
		List<AgentMessage> msgs = config.getSteeringMessages.get().join();
		return msgs != null ? msgs : new ArrayList<>();
	}

	private static List<AgentMessage> drainFollowUp(AgentLoopConfig config) {
		if (config.getFollowUpMessages == null) return new ArrayList<>();
		List<AgentMessage> msgs = config.getFollowUpMessages.get().join();
		return msgs != null ? msgs : new ArrayList<>();
	}

	private static <T> List<T> concat(List<T> a, List<T> b) {
		List<T> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	private static <T> T last(List<T> list) {
		return list.get(list.size() - 1);
	}

	private static String messageOf(Throwable e) {
		Throwable c = e;
		while (c.getCause() != null && c.getCause() != c) c = c.getCause();
		return c.getMessage() != null ? c.getMessage() : c.toString();
	}
}
