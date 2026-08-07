package com.duokanbook.pi.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * End-to-end demo of the Java agent core without any real LLM provider. A {@link FakeStreamFn}
 * simulates a model that, on turn 1, calls the {@code echo} tool, and on turn 2, replies with a
 * plain text summary. Run with {@code mvn compile exec:java}.
 *
 * <p>The expected event trace mirrors the "With Tool Calls" sequence in the principles doc, §7.
 */
public final class Main {

	public static void main(String[] args) throws Exception {
		Model model = new Model("fake-1", "Fake", "fake", "fake", "", false, 100_000, 4096,
				new Usage.Cost(0, 0, 0, 0, 0));

		Agent.AgentOptions options = new Agent.AgentOptions();
		options.model = model;
		options.systemPrompt = "You are a helpful demo agent.";
		options.tools = List.of(new EchoTool());
		options.streamFn = new FakeStreamFn();
		options.toolExecution = ToolExecutionMode.PARALLEL;

		Agent agent = new Agent(options);
		agent.subscribe((event, signal) -> {
			switch (event.type()) {
				case "message_start" -> System.out.println("  > " + event.type() + " " + describe(event));
				case "message_end" -> System.out.println("  > " + event.type() + " " + describe(event));
				case "tool_execution_start" -> System.out.println("  > tool_execution_start "
						+ ((AgentEvent.ToolExecutionStart) event).toolName()
						+ "(" + ((AgentEvent.ToolExecutionStart) event).args() + ")");
				case "tool_execution_end" -> System.out.println("  > tool_execution_end "
						+ ((AgentEvent.ToolExecutionEnd) event).toolName()
						+ " -> " + textOf(((AgentEvent.ToolExecutionEnd) event).result())
						+ (event instanceof AgentEvent.ToolExecutionEnd e && e.isError() ? " [ERROR]" : ""));
				case "turn_end" -> System.out.println("== turn_end ==");
				case "agent_end" -> System.out.println("== agent_end (" + ((AgentEvent.AgentEnd) event).messages().size() + " new messages) ==");
				default -> { /* turn_start, agent_start, message_update, tool_execution_update: quiet */ }
			}
		});

		System.out.println("### prompt: \"echo hello\"");
		agent.prompt("echo hello").join();

		System.out.println("\n### final transcript:");
		for (AgentMessage m : agent.state().messages()) {
			System.out.println("  - " + describe(m));
		}
	}

	// ───────────────────────── fake stream function ─────────────────────────

	/** Simulates a model: turn 1 → echo tool call; turn 2 → text reply using the tool result. */
	static final class FakeStreamFn implements StreamFn {
		@Override
		public EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> stream(
				Model model, LlmContext context, SimpleStreamOptions options) {

			EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> s = new EventStream<>();
			Thread t = new Thread(() -> {
				try {
					AgentMessage last = context.messages().get(context.messages().size() - 1);
					if ("user".equals(last.role())) {
						String text = userText((AgentMessage.UserMessage) last);
						Map<String, Object> args = new LinkedHashMap<>();
						args.put("text", text);
						AgentMessage.AssistantMessage msg = assistant(model, List.of(
								new Content.ToolCall("call_1", "echo", args)));
						streamMessage(s, model, msg);
					} else if ("toolResult".equals(last.role())) {
						String result = toolResultText((AgentMessage.ToolResultMessage) last);
						AgentMessage.AssistantMessage msg = assistant(model, List.of(
								new Content.Text("Tool replied: " + result)), StopReason.STOP);
						streamMessage(s, model, msg);
					} else {
						streamMessage(s, model, assistant(model, List.of(new Content.Text("(nothing to do)")), StopReason.STOP));
					}
				} catch (Throwable e) {
					s.completeExceptionally(e);
				}
			}, "pi-fake-stream");
			t.setDaemon(true);
			t.start();
			return s;
		}

		private static void streamMessage(EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> s,
				Model model, AgentMessage.AssistantMessage finalMessage) {
			AssistantMessageEvent partial0 = new AssistantMessageEvent.Start(snap(model, List.of(), StopReason.PENDING));
			s.push(partial0);

			List<Content> building = new ArrayList<>();
			for (Content c : finalMessage.content()) {
				int idx = building.size();
				if (c instanceof Content.Text t) {
					building.add(new Content.Text(""));
					s.push(new AssistantMessageEvent.TextStart(idx, snap(model, copy(building), StopReason.PENDING)));
					building.set(idx, new Content.Text(t.text()));
					s.push(new AssistantMessageEvent.TextDelta(idx, t.text(), snap(model, copy(building), StopReason.PENDING)));
					s.push(new AssistantMessageEvent.TextEnd(idx, t.text(), snap(model, copy(building), StopReason.PENDING)));
				} else if (c instanceof Content.ToolCall tc) {
					building.add(new Content.ToolCall(tc.id(), tc.name(), new LinkedHashMap<>()));
					s.push(new AssistantMessageEvent.ToolCallStart(idx, snap(model, copy(building), StopReason.PENDING)));
					String json = Json.stringify(tc.arguments());
					building.set(idx, new Content.ToolCall(tc.id(), tc.name(), tc.arguments()));
					s.push(new AssistantMessageEvent.ToolCallDelta(idx, json, snap(model, copy(building), StopReason.PENDING)));
					s.push(new AssistantMessageEvent.ToolCallEnd(idx, snap(model, copy(building), StopReason.PENDING)));
				}
			}
			AgentMessage.AssistantMessage done = snap(model, finalMessage.content(), StopReason.STOP);
			s.push(new AssistantMessageEvent.Done(StopReason.STOP, done));
			s.end(done);
		}

		private static AgentMessage.AssistantMessage snap(Model model, List<Content> content, StopReason reason) {
			return new AgentMessage.AssistantMessage(
					new ArrayList<>(content), model.api(), model.provider(), model.id(),
					Usage.empty(), reason, null, System.currentTimeMillis());
		}

		private static List<Content> copy(List<Content> src) {
			return new ArrayList<>(src);
		}

		private static AgentMessage.AssistantMessage assistant(Model model, List<Content> content) {
			return snap(model, content, StopReason.TOOL_USE);
		}

		private static AgentMessage.AssistantMessage assistant(Model model, List<Content> content, StopReason reason) {
			return snap(model, content, reason);
		}

		private static String userText(AgentMessage.UserMessage u) {
			for (Content c : u.content()) {
				if (c instanceof Content.Text t) return t.text();
			}
			return "";
		}

		private static String toolResultText(AgentMessage.ToolResultMessage t) {
			for (Content c : t.content()) {
				if (c instanceof Content.Text tx) return tx.text();
			}
			return "";
		}
	}

	// ───────────────────────── a sample tool ─────────────────────────

	/** Echoes its {@code text} argument, uppercased. */
	static final class EchoTool implements AgentTool {
		@Override public String name() { return "echo"; }
		@Override public String label() { return "echo"; }
		@Override public String description() { return "Echoes the given text, uppercased."; }

		@Override
		public Map<String, Object> parameters() {
			Map<String, Object> props = new LinkedHashMap<>();
			props.put("text", Map.of("type", "string"));
			Map<String, Object> schema = new LinkedHashMap<>();
			schema.put("type", "object");
			schema.put("properties", props);
			schema.put("required", List.of("text"));
			return schema;
		}

		@Override
		public CompletableFuture<AgentToolResult<?>> execute(
				String toolCallId, Object args, AbortSignal signal, Consumer<AgentToolResult<?>> onUpdate) {
			@SuppressWarnings("unchecked")
			Map<String, Object> a = (Map<String, Object>) args;
			String text = a.get("text") != null ? String.valueOf(a.get("text")) : "";
			return CompletableFuture.completedFuture(AgentToolResult.text("ECHO: " + text.toUpperCase()));
		}
	}

	// ───────────────────────── pretty-printing helpers ─────────────────────────

	private static String describe(AgentEvent e) {
		if (e instanceof AgentEvent.MessageStart ms) return describe(ms.message());
		if (e instanceof AgentEvent.MessageEnd me) return describe(me.message());
		return "";
	}

	private static String describe(AgentMessage m) {
		switch (m.role()) {
			case "user": return "user: " + textOf((AgentMessage.UserMessage) m);
			case "assistant": {
				StringBuilder sb = new StringBuilder("assistant:");
				for (Content c : ((AgentMessage.AssistantMessage) m).content()) {
					if (c instanceof Content.Text t) sb.append(" text=\"").append(t.text()).append("\"");
					if (c instanceof Content.ToolCall tc) sb.append(" toolCall=").append(tc.name()).append("(").append(tc.arguments()).append(")");
				}
				return sb.toString();
			}
			case "toolResult": return "toolResult(" + ((AgentMessage.ToolResultMessage) m).toolName() + "): " + textOf((AgentMessage.ToolResultMessage) m);
			default: return m.role();
		}
	}

	private static String textOf(AgentMessage.UserMessage u) {
		for (Content c : u.content()) if (c instanceof Content.Text t) return t.text();
		return "";
	}

	private static String textOf(AgentMessage.ToolResultMessage t) {
		for (Content c : t.content()) if (c instanceof Content.Text tx) return tx.text();
		return "";
	}

	private static String textOf(AgentToolResult<?> r) {
		if (r == null || r.content() == null) return "";
		for (Content c : r.content()) if (c instanceof Content.Text t) return t.text();
		return "";
	}
}
