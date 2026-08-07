package com.duokanbook.pi.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Stateful wrapper around the low-level {@link AgentLoop}, and a faithful port of {@code agent.ts}.
 *
 * <p>{@code Agent} owns the current transcript, emits lifecycle events via {@link #subscribe},
 * executes tools, and exposes queueing APIs for steering ({@link #steer}) and follow-up
 * ({@link #followUp}) messages. At most one run is active at a time; {@link #prompt} and
 * {@link #continueRun} throw if a run is already in flight — use the queues to inject messages
 * mid-run.
 *
 * <p>Threading: each run executes on a dedicated daemon thread; {@link #prompt} returns a
 * {@link CompletableFuture} that resolves after {@code agent_end} listeners settle. Event
 * processing ({@link #processEvents}) is synchronized so that concurrent tool-completion
 * emissions (parallel tool batch) serialize.
 */
public final class Agent {

	// ───────────────────────── listener ─────────────────────────

	/** Observer invoked for every {@link AgentEvent}, in subscription order, with the active signal. */
	@FunctionalInterface
	public interface AgentListener {
		void on(AgentEvent event, AbortSignal signal) throws Exception;
	}

	// ── Agent-level hook signatures (carry the active signal, like the TS wrapper) ──
	@FunctionalInterface
	public interface ShouldStopAfterTurnHook {
		CompletableFuture<Boolean> apply(AgentLoopConfig.TurnContext ctx, AbortSignal signal);
	}

	@FunctionalInterface
	public interface PrepareNextTurnHook {
		CompletableFuture<AgentLoopConfig.TurnUpdate> apply(AbortSignal signal);
	}

	@FunctionalInterface
	public interface PrepareNextTurnWithContextHook {
		CompletableFuture<AgentLoopConfig.TurnUpdate> apply(AgentLoopConfig.TurnContext ctx, AbortSignal signal);
	}

	/** Mutable agent state. {@code tools}/{@code messages} setters copy their top-level list. */
	public static final class State {
		public String systemPrompt = "";
		public Model model = Model.unknown();
		public ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
		private List<AgentTool> tools = new ArrayList<>();
		private List<AgentMessage> messages = new ArrayList<>();
		public volatile boolean isStreaming = false;
		public volatile AgentMessage streamingMessage = null;
		// Mirrors the TS original: replaced wholesale (copy-on-write) on each mutation rather than
		// mutated in place, so an external thread reading this reference always sees a stable,
		// unmodifiable snapshot instead of racing with processEvents()'s add/remove.
		public volatile Set<String> pendingToolCalls = Set.of();
		public volatile String errorMessage = null;

		public List<AgentTool> tools() {
			return tools;
		}

		public void setTools(List<AgentTool> next) {
			tools = new ArrayList<>(next);
		}

		public List<AgentMessage> messages() {
			return messages;
		}

		public void setMessages(List<AgentMessage> next) {
			messages = new ArrayList<>(next);
		}
	}

	private static final AgentLoopConfig.ConvertToLlm DEFAULT_CONVERT_TO_LLM = messages -> {
		List<AgentMessage> out = new ArrayList<>();
		for (AgentMessage m : messages) {
			String role = m.role();
			if ("user".equals(role) || "assistant".equals(role) || "toolResult".equals(role)) {
				out.add(m);
			}
		}
		return CompletableFuture.completedFuture(out);
	};

	private final State state = new State();
	// CopyOnWriteArraySet: processEvents() iterates this on the run thread while subscribe()/the
	// returned unsubscriber may add/remove from any thread — including from inside a listener
	// callback (self-unsubscribe). A plain HashSet iterated concurrently with such a mutation
	// throws ConcurrentModificationException; this iterates a stable snapshot instead.
	private final Set<AgentListener> listeners = new CopyOnWriteArraySet<>();
	private final PendingMessageQueue steeringQueue = new PendingMessageQueue(QueueMode.ONE_AT_A_TIME);
	private final PendingMessageQueue followUpQueue = new PendingMessageQueue(QueueMode.ONE_AT_A_TIME);

	// Public hook/config fields (mirror the TS Agent's public fields).
	public AgentLoopConfig.ConvertToLlm convertToLlm = null;
	public AgentLoopConfig.TransformContext transformContext = null;
	public StreamFn streamFunction = null;
	public AgentLoopConfig.GetApiKey getApiKey = null;
	public AgentLoopConfig.BeforeToolCall beforeToolCall = null;
	public AgentLoopConfig.AfterToolCall afterToolCall = null;
	public ShouldStopAfterTurnHook shouldStopAfterTurn = null;
	public PrepareNextTurnHook prepareNextTurn = null;
	public PrepareNextTurnWithContextHook prepareNextTurnWithContext = null;
	public String sessionId = null;
	public Object thinkingBudgets = null;
	public String transport = "auto";
	public Long maxRetryDelayMs = null;
	public ToolExecutionMode toolExecution = ToolExecutionMode.PARALLEL;

	private volatile ActiveRun activeRun = null;

	private static final class ActiveRun {
		final AbortSignal signal = new AbortSignal();
		final CompletableFuture<Void> done = new CompletableFuture<>();
	}

	/** Options for constructing an {@link Agent}. Set fields after {@code new AgentOptions()}. */
	public static final class AgentOptions {
		public String systemPrompt = "";
		public Model model = Model.unknown();
		public ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
		public List<AgentTool> tools = null;
		public List<AgentMessage> messages = null;
		public AgentLoopConfig.ConvertToLlm convertToLlm = null;
		public AgentLoopConfig.TransformContext transformContext = null;
		public StreamFn streamFn = null;
		public AgentLoopConfig.GetApiKey getApiKey = null;
		public AgentLoopConfig.BeforeToolCall beforeToolCall = null;
		public AgentLoopConfig.AfterToolCall afterToolCall = null;
		public ShouldStopAfterTurnHook shouldStopAfterTurn = null;
		public PrepareNextTurnHook prepareNextTurn = null;
		public PrepareNextTurnWithContextHook prepareNextTurnWithContext = null;
		public QueueMode steeringMode = QueueMode.ONE_AT_A_TIME;
		public QueueMode followUpMode = QueueMode.ONE_AT_A_TIME;
		public String sessionId = null;
		public Object thinkingBudgets = null;
		public String transport = "auto";
		public Long maxRetryDelayMs = null;
		public ToolExecutionMode toolExecution = ToolExecutionMode.PARALLEL;
	}

	public Agent(AgentOptions options) {
		AgentOptions o = options != null ? options : new AgentOptions();
		state.systemPrompt = o.systemPrompt;
		state.model = o.model;
		state.thinkingLevel = o.thinkingLevel;
		if (o.tools != null) state.setTools(o.tools);
		if (o.messages != null) state.setMessages(o.messages);
		convertToLlm = o.convertToLlm;
		transformContext = o.transformContext;
		streamFunction = o.streamFn;
		getApiKey = o.getApiKey;
		beforeToolCall = o.beforeToolCall;
		afterToolCall = o.afterToolCall;
		shouldStopAfterTurn = o.shouldStopAfterTurn;
		prepareNextTurn = o.prepareNextTurn;
		prepareNextTurnWithContext = o.prepareNextTurnWithContext;
		steeringQueue.mode = o.steeringMode;
		followUpQueue.mode = o.followUpMode;
		sessionId = o.sessionId;
		thinkingBudgets = o.thinkingBudgets;
		transport = o.transport;
		maxRetryDelayMs = o.maxRetryDelayMs;
		toolExecution = o.toolExecution;
	}

	// ───────────────────────── subscriptions ─────────────────────────

	/** Subscribe to lifecycle events. Returns an unsubscriber. */
	public Runnable subscribe(AgentListener listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	public State state() {
		return state;
	}

	// ───────────────────────── queues ─────────────────────────

	public void setSteeringMode(QueueMode mode) {
		steeringQueue.mode = mode;
	}

	public QueueMode steeringMode() {
		return steeringQueue.mode;
	}

	public void setFollowUpMode(QueueMode mode) {
		followUpQueue.mode = mode;
	}

	public QueueMode followUpMode() {
		return followUpQueue.mode;
	}

	/** Queue a message injected after the current assistant turn finishes. */
	public void steer(AgentMessage message) {
		steeringQueue.enqueue(message);
	}

	/** Queue a message that runs only after the agent would otherwise stop. */
	public void followUp(AgentMessage message) {
		followUpQueue.enqueue(message);
	}

	public void clearSteeringQueue() {
		steeringQueue.clear();
	}

	public void clearFollowUpQueue() {
		followUpQueue.clear();
	}

	public void clearAllQueues() {
		steeringQueue.clear();
		followUpQueue.clear();
	}

	public boolean hasQueuedMessages() {
		return !steeringQueue.isEmpty() || !followUpQueue.isEmpty();
	}

	// ───────────────────────── run control ─────────────────────────

	public AbortSignal signal() {
		return activeRun != null ? activeRun.signal : null;
	}

	public void abort() {
		if (activeRun != null) activeRun.signal.abort();
	}

	public CompletableFuture<Void> waitForIdle() {
		return activeRun != null ? activeRun.done : CompletableFuture.completedFuture(null);
	}

	public void reset() {
		if (activeRun != null) {
			throw new IllegalStateException("Agent is already processing. Wait for completion before resetting.");
		}
		state.messages.clear();
		state.isStreaming = false;
		state.streamingMessage = null;
		state.pendingToolCalls = Set.of();
		state.errorMessage = null;
		clearAllQueues();
	}

	// ───────────────────────── prompt / continue ─────────────────────────

	public CompletableFuture<Void> prompt(String text) {
		return prompt(List.<AgentMessage>of(new AgentMessage.UserMessage(text)));
	}

	public CompletableFuture<Void> prompt(AgentMessage message) {
		return prompt(List.of(message));
	}

	public CompletableFuture<Void> prompt(List<AgentMessage> messages) {
		if (activeRun != null) {
			throw new IllegalStateException(
					"Agent is already processing a prompt. Use steer()/followUp() to queue messages.");
		}
		return runPromptMessages(messages, false);
	}

	/** Continue from the current transcript. Last message must be user/toolResult (or have queued steering/followUp). */
	public CompletableFuture<Void> continueRun() {
		if (activeRun != null) {
			throw new IllegalStateException("Agent is already processing. Wait for completion before continuing.");
		}
		if (state.messages.isEmpty()) {
			throw new IllegalStateException("No messages to continue from");
		}
		AgentMessage last = state.messages.get(state.messages.size() - 1);
		if ("assistant".equals(last.role())) {
			List<AgentMessage> queuedSteering = steeringQueue.drain();
			if (!queuedSteering.isEmpty()) {
				return runPromptMessages(queuedSteering, true);
			}
			List<AgentMessage> queuedFollowUps = followUpQueue.drain();
			if (!queuedFollowUps.isEmpty()) {
				return runPromptMessages(queuedFollowUps, false);
			}
			throw new IllegalStateException("Cannot continue from message role: assistant");
		}
		return runWithLifecycle(signal ->
				AgentLoop.runAgentLoopContinue(createContextSnapshot(), createLoopConfig(false, signal),
						this::processEvents, signal, effectiveStreamFn()));
	}

	private CompletableFuture<Void> runPromptMessages(List<AgentMessage> messages, boolean skipInitialSteeringPoll) {
		return runWithLifecycle(signal ->
				AgentLoop.runAgentLoop(messages, createContextSnapshot(), createLoopConfig(skipInitialSteeringPoll, signal),
						this::processEvents, signal, effectiveStreamFn()));
	}

	private StreamFn effectiveStreamFn() {
		return streamFunction != null ? streamFunction : DefaultStreamFn.get();
	}

	private AgentContext createContextSnapshot() {
		return new AgentContext(state.systemPrompt, new ArrayList<>(state.messages), new ArrayList<>(state.tools));
	}

	private AgentLoopConfig createLoopConfig(boolean skipInitialSteeringPoll, AbortSignal signal) {
		AgentLoopConfig c = new AgentLoopConfig();
		c.model = state.model;
		c.reasoning = state.thinkingLevel == ThinkingLevel.OFF ? null : state.thinkingLevel;
		c.sessionId = sessionId;
		c.transport = transport;
		c.thinkingBudgets = thinkingBudgets;
		c.maxRetryDelayMs = maxRetryDelayMs;
		c.toolExecution = toolExecution;
		c.convertToLlm = convertToLlm != null ? convertToLlm : DEFAULT_CONVERT_TO_LLM;
		c.transformContext = transformContext;
		c.getApiKey = getApiKey;
		c.beforeToolCall = beforeToolCall;
		c.afterToolCall = afterToolCall;

		final ShouldStopAfterTurnHook sst = shouldStopAfterTurn;
		c.shouldStopAfterTurn = sst != null ? ctx -> sst.apply(ctx, signal) : null;

		final PrepareNextTurnWithContextHook pntCtx = prepareNextTurnWithContext;
		final PrepareNextTurnHook pnt = prepareNextTurn;
		if (pntCtx != null) {
			c.prepareNextTurn = ctx -> pntCtx.apply(ctx, signal);
		} else if (pnt != null) {
			c.prepareNextTurn = ctx -> pnt.apply(signal);
		} else {
			c.prepareNextTurn = null;
		}

		// The skipInitialSteeringPoll flag is consumed on the first drain of a run.
		final boolean[] skip = {skipInitialSteeringPoll};
		c.getSteeringMessages = () -> {
			if (skip[0]) {
				skip[0] = false;
				return CompletableFuture.completedFuture(List.of());
			}
			return CompletableFuture.completedFuture(steeringQueue.drain());
		};
		c.getFollowUpMessages = () -> CompletableFuture.completedFuture(followUpQueue.drain());
		return c;
	}

	// ───────────────────────── lifecycle ─────────────────────────

	@FunctionalInterface
	private interface Task {
		void run(AbortSignal signal) throws Exception;
	}

	private CompletableFuture<Void> runWithLifecycle(Task executor) {
		if (activeRun != null) {
			throw new IllegalStateException("Agent is already processing.");
		}
		ActiveRun run = new ActiveRun();
		activeRun = run;
		state.isStreaming = true;
		state.streamingMessage = null;
		state.errorMessage = null;

		Thread t = new Thread(() -> {
			try {
				executor.run(run.signal);
			} catch (Throwable e) {
				handleRunFailure(e, run.signal.isAborted());
			} finally {
				finishRun();
			}
		}, "pi-agent-run");
		t.setDaemon(true);
		t.start();
		return run.done;
	}

	private void handleRunFailure(Throwable error, boolean aborted) {
		try {
			AgentMessage.AssistantMessage failure = new AgentMessage.AssistantMessage(
					List.of(new Content.Text("")),
					state.model.api(), state.model.provider(), state.model.id(),
					Usage.empty(),
					aborted ? StopReason.ABORTED : StopReason.ERROR,
					error instanceof Exception ? error.getMessage() : error.toString(),
					System.currentTimeMillis());
			processEvents(new AgentEvent.MessageStart(failure));
			processEvents(new AgentEvent.MessageEnd(failure));
			processEvents(new AgentEvent.TurnEnd(failure, List.of()));
			processEvents(new AgentEvent.AgentEnd(List.of(failure)));
		} catch (Throwable ignored) {
			// Best-effort: ensure finishRun() still runs and the idle future resolves.
		}
	}

	private void finishRun() {
		state.isStreaming = false;
		state.streamingMessage = null;
		state.pendingToolCalls = Set.of();
		ActiveRun run = activeRun;
		activeRun = null;
		if (run != null) run.done.complete(null);
	}

	/** Reduce internal state for an event, then await listeners (synchronized: serializes parallel tool emits). */
	private synchronized void processEvents(AgentEvent event) throws Exception {
		switch (event.type()) {
			case "message_start" -> state.streamingMessage = ((AgentEvent.MessageStart) event).message();
			case "message_update" -> state.streamingMessage = ((AgentEvent.MessageUpdate) event).message();
			case "message_end" -> {
				state.streamingMessage = null;
				state.messages.add(((AgentEvent.MessageEnd) event).message());
			}
			case "tool_execution_start" -> {
				Set<String> next = new HashSet<>(state.pendingToolCalls);
				next.add(((AgentEvent.ToolExecutionStart) event).toolCallId());
				state.pendingToolCalls = next;
			}
			case "tool_execution_end" -> {
				Set<String> next = new HashSet<>(state.pendingToolCalls);
				next.remove(((AgentEvent.ToolExecutionEnd) event).toolCallId());
				state.pendingToolCalls = next;
			}
			case "turn_end" -> {
				AgentMessage m = ((AgentEvent.TurnEnd) event).message();
				if (m instanceof AgentMessage.AssistantMessage a && a.errorMessage() != null) {
					state.errorMessage = a.errorMessage();
				}
			}
			case "agent_end" -> state.streamingMessage = null;
			default -> { /* agent_start, turn_start: no state change */ }
		}

		AbortSignal signal = activeRun != null ? activeRun.signal : null;
		for (AgentListener listener : listeners) {
			listener.on(event, signal);
		}
	}

	// ───────────────────────── pending message queue ─────────────────────────

	private static final class PendingMessageQueue {
		private final List<AgentMessage> messages = new ArrayList<>();
		QueueMode mode;

		PendingMessageQueue(QueueMode mode) {
			this.mode = mode;
		}

		void enqueue(AgentMessage m) {
			messages.add(m);
		}

		boolean isEmpty() {
			return messages.isEmpty();
		}

		List<AgentMessage> drain() {
			if (mode == QueueMode.ALL) {
				List<AgentMessage> drained = new ArrayList<>(messages);
				messages.clear();
				return drained;
			}
			if (messages.isEmpty()) return List.of();
			AgentMessage first = messages.remove(0);
			return new ArrayList<>(List.of(first));
		}

		void clear() {
			messages.clear();
		}
	}
}
