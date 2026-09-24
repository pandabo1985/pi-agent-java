package com.duokanbook.pi.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
		AgentLoopConfig.TurnContext lastCompletedTurn = null;
		boolean explicitContinuation = false;
		int assistantTurns = 0;
		// Steering messages may have arrived while the caller was waiting to start.
		List<AgentMessage> pendingMessages = drainSteering(config);

		outer:
		while (true) {
			boolean hasMoreToolCalls = true;

			// Inner loop: consume tool-result continuations and steering messages.
			while (hasMoreToolCalls || !pendingMessages.isEmpty()) {
				if (signal != null) signal.check();

				List<AgentMessage> preparedMessages = new ArrayList<>();
				if (lastCompletedTurn != null) {
					AgentLoopConfig.TurnUpdate upd = config.prepareNextTurn == null ? null
							: config.prepareNextTurn.apply(lastCompletedTurn).join();
					if (upd != null) {
						if (upd.context() != null) currentContext = upd.context();
						if (upd.messages() != null) preparedMessages.addAll(upd.messages());
						if (upd.model() != null) config.model = upd.model();
						if (upd.thinkingLevel() != null) {
							config.reasoning = upd.thinkingLevel() == ThinkingLevel.OFF ? null : upd.thinkingLevel();
						}
					}

					// Preparation can be slow (for example, compaction). Pick up steering that
					// arrived while it ran, but do not double-drain one-at-a-time queues.
					if (pendingMessages.isEmpty()) {
						pendingMessages = drainSteering(config);
					}
					emit.emit(new AgentEvent.TurnStart());
				}

				// Prepared messages and queued user input are normal transcript messages and
				// therefore receive the same message lifecycle events.
				List<AgentMessage> selectedMessages = new ArrayList<>(preparedMessages);
				selectedMessages.addAll(pendingMessages);
				for (AgentMessage message : selectedMessages) {
					emit.emit(new AgentEvent.MessageStart(message));
					emit.emit(new AgentEvent.MessageEnd(message));
					currentContext.messages.add(message);
					newMessages.add(message);
				}
				pendingMessages = new ArrayList<>();

				if (config.maxTurns != null && config.maxTurns > 0 && assistantTurns >= config.maxTurns) {
					AgentMessage.AssistantMessage failure = new AgentMessage.AssistantMessage(
							Collections.<Content>singletonList(new Content.Text("Agent turn limit exceeded")),
							config.model.api(), config.model.provider(), config.model.id(), Usage.empty(),
							StopReason.ERROR, "Maximum assistant turns exceeded: " + config.maxTurns,
							System.currentTimeMillis());
					newMessages.add(failure);
					emit.emit(new AgentEvent.MessageStart(failure));
					emit.emit(new AgentEvent.MessageEnd(failure));
					AgentLoopConfig.TurnContext limitTurn = new AgentLoopConfig.TurnContext(
							failure, Collections.<AgentMessage.ToolResultMessage>emptyList(), currentContext, newMessages);
					if (config.finishTurn != null) config.finishTurn.apply(limitTurn, signal).join();
					emit.emit(new AgentEvent.TurnEnd(failure, Collections.<AgentMessage.ToolResultMessage>emptyList()));
					emit.emit(new AgentEvent.AgentEnd(newMessages));
					return;
				}

				// Final request-preparation seam. Pending messages are already visible here.
				if (config.prepareRequest != null) {
					ThinkingLevel thinking = config.reasoning != null ? config.reasoning : ThinkingLevel.OFF;
					AgentLoopConfig.RequestUpdate requestUpdate = config.prepareRequest.apply(
							new AgentLoopConfig.PrepareRequestContext(currentContext, config.model, thinking), signal).join();
					if (requestUpdate != null) {
						if (requestUpdate.context() != null) currentContext = requestUpdate.context();
						if (requestUpdate.model() != null) config.model = requestUpdate.model();
						if (requestUpdate.thinkingLevel() != null) {
							config.reasoning = requestUpdate.thinkingLevel() == ThinkingLevel.OFF
									? null : requestUpdate.thinkingLevel();
						}
					}
				}

				// Stream one assistant response.
				AgentMessage.AssistantMessage message = streamAssistantResponse(
						currentContext, config, signal, emit, streamFunction);
				assistantTurns++;
				newMessages.add(message);

				if (message.stopReason() == StopReason.ERROR || message.stopReason() == StopReason.ABORTED) {
					lastCompletedTurn = new AgentLoopConfig.TurnContext(
							message, Collections.<AgentMessage.ToolResultMessage>emptyList(), currentContext, newMessages);
					if (config.finishTurn != null) config.finishTurn.apply(lastCompletedTurn, signal).join();
					emit.emit(new AgentEvent.TurnEnd(message, Collections.<AgentMessage.ToolResultMessage>emptyList()));
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

				lastCompletedTurn = new AgentLoopConfig.TurnContext(
						message, toolResults, currentContext, newMessages);

				AgentLoopConfig.TurnDecision decision = config.finishTurn == null ? null
						: config.finishTurn.apply(lastCompletedTurn, signal).join();

				// Backward compatibility for the pre-finishTurn Java API. It intentionally runs
				// only on normal responses, matching the historical shouldStopAfterTurn behavior.
				if (decision == null && config.finishTurn == null && config.shouldStopAfterTurn != null
						&& config.shouldStopAfterTurn.apply(lastCompletedTurn).join()) {
					decision = AgentLoopConfig.TurnDecision.endRun();
				}

				emit.emit(new AgentEvent.TurnEnd(message, toolResults));

				if (decision != null && decision.action() == AgentLoopConfig.TurnAction.END) {
					emit.emit(new AgentEvent.AgentEnd(newMessages));
					return;
				}

				explicitContinuation = decision != null
						&& decision.action() == AgentLoopConfig.TurnAction.CONTINUE;

				pendingMessages = drainSteering(config);
				if (hasMoreToolCalls || !pendingMessages.isEmpty()) {
					explicitContinuation = false;
				}
			}

			// Agent would stop here — check for follow-up messages.
			List<AgentMessage> followUps = drainFollowUp(config);
			if (!followUps.isEmpty()) {
				explicitContinuation = false;
				pendingMessages = followUps;
				continue outer;
			}

			// No natural continuation was selected; fulfill an explicit CONTINUE with
			// exactly one context-only provider turn.
			if (explicitContinuation) {
				explicitContinuation = false;
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
				.temperature(config.temperature)
				.samplingParams(config.samplingParams)
				.maxTokens(config.maxTokens)
				.reasoning(config.reasoning)
				.cacheRetention(config.cacheRetention)
				.sessionId(config.sessionId)
				.headers(config.headers)
				.metadata(config.metadata)
				.transport(config.transport)
				.thinkingBudgets(config.thinkingBudgets)
				.maxRetryDelayMs(config.maxRetryDelayMs)
				.onPayload(config.onPayload)
				.onResponse(config.onResponse)
				.build();

		EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> response =
				streamFunction.stream(config.model, llmContext, options);

		AgentMessage.AssistantMessage partial = null;
		boolean addedPartial = false;

		for (AssistantMessageEvent event : response) {
			String type = event.type();
			if ("start".equals(type)) {
				partial = event.partialMessage();
				context.messages.add(partial);
				addedPartial = true;
				emit.emit(new AgentEvent.MessageStart(partial));
			} else if ("text_start".equals(type) || "text_delta".equals(type) || "text_end".equals(type)
					|| "thinking_start".equals(type) || "thinking_delta".equals(type) || "thinking_end".equals(type)
					|| "toolcall_start".equals(type) || "toolcall_delta".equals(type) || "toolcall_end".equals(type)) {
				if (partial != null) {
					partial = event.partialMessage();
					context.messages.set(context.messages.size() - 1, partial);
					emit.emit(new AgentEvent.MessageUpdate(partial, event));
				}
			} else if ("done".equals(type) || "error".equals(type)) {
				return finalizeAssistant(response, context, partial, addedPartial, emit);
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

	private static final class ExecutedToolCallBatch extends ValueObject {
		private final List<AgentMessage.ToolResultMessage> messages;
		private final boolean terminate;
		ExecutedToolCallBatch(List<AgentMessage.ToolResultMessage> messages, boolean terminate) { this.messages = messages; this.terminate = terminate; }
		List<AgentMessage.ToolResultMessage> messages() { return messages; }
		boolean terminate() { return terminate; }
		@Override protected String[] componentNames() { return new String[] {"messages", "terminate"}; }
		@Override protected Object[] componentValues() { return new Object[] {messages, terminate}; }
	}
	private interface Preparation {}
	private static final class Prepared extends ValueObject implements Preparation {
		private final Content.ToolCall toolCall; private final AgentTool tool; private final Object args;
		Prepared(Content.ToolCall toolCall, AgentTool tool, Object args) { this.toolCall = toolCall; this.tool = tool; this.args = args; }
		Content.ToolCall toolCall() { return toolCall; } AgentTool tool() { return tool; } Object args() { return args; }
		@Override protected String[] componentNames() { return new String[] {"toolCall", "tool", "args"}; }
		@Override protected Object[] componentValues() { return new Object[] {toolCall, tool, args}; }
	}
	private static final class Immediate extends ValueObject implements Preparation {
		private final AgentToolResult<?> result; private final boolean isError;
		Immediate(AgentToolResult<?> result, boolean isError) { this.result = result; this.isError = isError; }
		AgentToolResult<?> result() { return result; } boolean isError() { return isError; }
		@Override protected String[] componentNames() { return new String[] {"result", "isError"}; }
		@Override protected Object[] componentValues() { return new Object[] {result, isError}; }
	}
	private static final class ExecutedToolCallOutcome extends ValueObject {
		private final AgentToolResult<?> result; private final boolean isError;
		ExecutedToolCallOutcome(AgentToolResult<?> result, boolean isError) { this.result = result; this.isError = isError; }
		AgentToolResult<?> result() { return result; } boolean isError() { return isError; }
		@Override protected String[] componentNames() { return new String[] {"result", "isError"}; }
		@Override protected Object[] componentValues() { return new Object[] {result, isError}; }
	}
	private static final class FinalizedToolCall extends ValueObject {
		private final Content.ToolCall toolCall; private final AgentToolResult<?> result; private final boolean isError;
		FinalizedToolCall(Content.ToolCall toolCall, AgentToolResult<?> result, boolean isError) { this.toolCall = toolCall; this.result = result; this.isError = isError; }
		Content.ToolCall toolCall() { return toolCall; } AgentToolResult<?> result() { return result; } boolean isError() { return isError; }
		@Override protected String[] componentNames() { return new String[] {"toolCall", "result", "isError"}; }
		@Override protected Object[] componentValues() { return new Object[] {toolCall, result, isError}; }
	}

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
			if (preparation instanceof Immediate) {
				Immediate imm = (Immediate) preparation;
				finalized = new FinalizedToolCall(toolCall, imm.result(), imm.isError());
			} else {
				Prepared prepared = (Prepared) preparation;
				ExecutedToolCallOutcome executed = executePrepared(prepared, config, signal, emit);
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
			if (preparation instanceof Immediate) {
				Immediate imm = (Immediate) preparation;
				FinalizedToolCall finalized = new FinalizedToolCall(toolCall, imm.result(), imm.isError());
				emit.emit(new AgentEvent.ToolExecutionEnd(toolCall.id(), toolCall.name(), finalized.result, finalized.isError));
				entries.add(() -> CompletableFuture.completedFuture(finalized));
				if (signal != null && signal.isAborted()) break;
				continue;
			}

			Prepared prepared = (Prepared) preparation;
			entries.add(() -> {
				FinalizedToolCall finalized;
				if (signal != null && signal.isAborted()) {
					finalized = new FinalizedToolCall(prepared.toolCall(),
							AgentToolResult.error("Operation aborted"), true);
				} else {
					ExecutedToolCallOutcome executed = executePrepared(prepared, config, signal, emit);
					finalized = finalizeExecuted(currentContext, assistantMessage, prepared, executed, config, signal);
				}
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
			return new Immediate(AgentToolResult.error("Tool " + toolCall.name() + " not found"), true);
		}
		try {
			Object validatedArgs = validateToolArguments(tool, toolCall);
			if (config.beforeToolCall != null) {
				AgentLoopConfig.BeforeResult before = config.beforeToolCall.apply(
						new AgentLoopConfig.BeforeToolCallContext(assistantMessage, toolCall, validatedArgs, currentContext),
						signal).join();
				if (signal != null && signal.isAborted()) {
					return new Immediate(AgentToolResult.error("Operation aborted"), true);
				}
				if (before != null && before.blocked()) {
					AgentToolResult<Object> r = AgentToolResult.error(before.reason() != null ? before.reason() : "Tool execution was blocked");
					if (before.shouldTerminate()) {
						r = new AgentToolResult<>(r.content(), r.details(), r.usage(), r.addedToolNames(), true);
					}
					return new Immediate(r, true);
				}
			}
			if (signal != null && signal.isAborted()) {
				return new Immediate(AgentToolResult.error("Operation aborted"), true);
			}
			return new Prepared(toolCall, tool, validatedArgs);
		} catch (Throwable e) {
			return new Immediate(AgentToolResult.error(messageOf(e)), true);
		}
	}

	private static ExecutedToolCallOutcome executePrepared(
			Prepared prepared, AgentLoopConfig config, AbortSignal signal, EventSink emit) {

		AtomicBoolean accepting = new AtomicBoolean(true);
		String id = prepared.toolCall().id();
		String name = prepared.toolCall().name();
		Object args = prepared.toolCall().arguments();
		try {
			CompletableFuture<AgentToolResult<?>> future = prepared.tool().execute(id, prepared.args(), signal, partial -> {
				if (!accepting.get()) return;
				try {
					emit.emit(new AgentEvent.ToolExecutionUpdate(id, name, args, partial));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			AgentToolResult<?> result = awaitToolResult(future, config.toolTimeoutMs, signal, prepared.toolCall().name());
			return new ExecutedToolCallOutcome(result, false);
		} catch (Throwable e) {
			return new ExecutedToolCallOutcome(AgentToolResult.error(messageOf(e)), true);
		} finally {
			accepting.set(false);
		}
	}

	private static AgentToolResult<?> awaitToolResult(
			CompletableFuture<AgentToolResult<?>> future, Long timeoutMs, AbortSignal signal, String toolName) throws Exception {
		long deadline = timeoutMs != null && timeoutMs > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs) : 0L;
		while (true) {
			if (signal != null && signal.isAborted()) {
				future.cancel(true);
				throw new AbortSignal.AbortedException("Operation aborted");
			}
			long waitMs = 100L;
			if (deadline != 0L) {
				long remainingNanos = deadline - System.nanoTime();
				if (remainingNanos <= 0L) {
					future.cancel(true);
					throw new TimeoutException("Tool " + toolName + " timed out after " + timeoutMs + " ms");
				}
				waitMs = Math.min(waitMs, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
			}
			try {
				return future.get(waitMs, TimeUnit.MILLISECONDS);
			} catch (TimeoutException ignored) {
				// Recheck abort and deadline without blocking the agent run indefinitely.
			} catch (ExecutionException e) {
				Throwable cause = e.getCause();
				if (cause instanceof Exception) throw (Exception) cause;
				if (cause instanceof Error) throw (Error) cause;
				throw new RuntimeException(cause);
			}
		}
	}

	private static FinalizedToolCall finalizeExecuted(
			AgentContext currentContext,
			AgentMessage.AssistantMessage assistantMessage,
			Prepared prepared,
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
		// addedToolNames is retained for provider/deferred-tool consumers; the core loop does not
		// mutate currentContext.tools because it has no registry from which to resolve names.
		return new AgentMessage.ToolResultMessage(
				finalized.toolCall().id(),
				finalized.toolCall().name(),
				r.content() != null ? r.content() : Collections.<Content>emptyList(),
				r.details(),
				r.usage(),
				r.addedToolNames(),
				finalized.isError(),
				System.currentTimeMillis());
	}

	// ───────────────────────── validation & helpers ─────────────────────────

	/** Apply {@link AgentTool#prepareArguments(Object)} then coerce and validate against the schema. */
	private static Object validateToolArguments(AgentTool tool, Content.ToolCall toolCall) {
		Object args = tool.prepareArguments(toolCall.arguments());
		Map<String, Object> schema = tool.parameters();
		return schema != null ? JsonSchemaValidator.coerceAndValidate(toolCall.name(), args, schema) : args;
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
			if (c instanceof Content.ToolCall) out.add((Content.ToolCall) c);
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
