package com.duokanbook.pi.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
		options.tools = Collections.<AgentTool>singletonList(new EchoTool());
		options.streamFn = new FakeStreamFn();
		options.toolExecution = ToolExecutionMode.PARALLEL;

		Agent agent = new Agent(options);
		agent.subscribe((event, signal) -> {
			if ("message_start".equals(event.type()) || "message_end".equals(event.type())) System.out.println("  > " + event.type() + " " + describe(event));
			else if ("tool_execution_start".equals(event.type())) System.out.println("  > tool_execution_start " + ((AgentEvent.ToolExecutionStart) event).toolName() + "(" + ((AgentEvent.ToolExecutionStart) event).args() + ")");
			else if ("tool_execution_end".equals(event.type())) {
				AgentEvent.ToolExecutionEnd end = (AgentEvent.ToolExecutionEnd) event;
				System.out.println("  > tool_execution_end " + end.toolName() + " -> " + textOf(end.result()) + (end.isError() ? " [ERROR]" : ""));
			} else if ("turn_end".equals(event.type())) System.out.println("== turn_end ==");
			else if ("agent_end".equals(event.type())) System.out.println("== agent_end (" + ((AgentEvent.AgentEnd) event).messages().size() + " new messages) ==");
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
						AgentMessage.AssistantMessage msg = assistant(model, Collections.<Content>singletonList(
								new Content.ToolCall("call_1", "echo", args)));
						streamMessage(s, model, msg);
					} else if ("toolResult".equals(last.role())) {
						String result = toolResultText((AgentMessage.ToolResultMessage) last);
						AgentMessage.AssistantMessage msg = assistant(model, Collections.<Content>singletonList(
								new Content.Text("Tool replied: " + result)), StopReason.STOP);
						streamMessage(s, model, msg);
					} else {
						streamMessage(s, model, assistant(model, Collections.<Content>singletonList(new Content.Text("(nothing to do)")), StopReason.STOP));
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
			AssistantMessageEvent partial0 = new AssistantMessageEvent.Start(snap(model, Collections.<Content>emptyList(), StopReason.PENDING));
			s.push(partial0);

			List<Content> building = new ArrayList<>();
			for (Content c : finalMessage.content()) {
				int idx = building.size();
				if (c instanceof Content.Text) {
					Content.Text t = (Content.Text) c;
					building.add(new Content.Text(""));
					s.push(new AssistantMessageEvent.TextStart(idx, snap(model, copy(building), StopReason.PENDING)));
					building.set(idx, new Content.Text(t.text()));
					s.push(new AssistantMessageEvent.TextDelta(idx, t.text(), snap(model, copy(building), StopReason.PENDING)));
					s.push(new AssistantMessageEvent.TextEnd(idx, t.text(), snap(model, copy(building), StopReason.PENDING)));
				} else if (c instanceof Content.ToolCall) {
					Content.ToolCall tc = (Content.ToolCall) c;
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
				if (c instanceof Content.Text) return ((Content.Text) c).text();
			}
			return "";
		}

		private static String toolResultText(AgentMessage.ToolResultMessage t) {
			for (Content c : t.content()) {
				if (c instanceof Content.Text) return ((Content.Text) c).text();
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
			Map<String, Object> textSchema = new LinkedHashMap<>();
			textSchema.put("type", "string");
			props.put("text", textSchema);
			Map<String, Object> schema = new LinkedHashMap<>();
			schema.put("type", "object");
			schema.put("properties", props);
			schema.put("required", Collections.singletonList("text"));
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
		if (e instanceof AgentEvent.MessageStart) return describe(((AgentEvent.MessageStart) e).message());
		if (e instanceof AgentEvent.MessageEnd) return describe(((AgentEvent.MessageEnd) e).message());
		return "";
	}

	private static String describe(AgentMessage m) {
		switch (m.role()) {
			case "user": return "user: " + textOf((AgentMessage.UserMessage) m);
			case "assistant": {
				StringBuilder sb = new StringBuilder("assistant:");
				for (Content c : ((AgentMessage.AssistantMessage) m).content()) {
					if (c instanceof Content.Text) sb.append(" text=\"").append(((Content.Text) c).text()).append("\"");
					if (c instanceof Content.ToolCall) sb.append(" toolCall=").append(((Content.ToolCall) c).name()).append("(").append(((Content.ToolCall) c).arguments()).append(")");
				}
				return sb.toString();
			}
			case "toolResult": return "toolResult(" + ((AgentMessage.ToolResultMessage) m).toolName() + "): " + textOf((AgentMessage.ToolResultMessage) m);
			default: return m.role();
		}
	}

	private static String textOf(AgentMessage.UserMessage u) {
		for (Content c : u.content()) if (c instanceof Content.Text) return ((Content.Text) c).text();
		return "";
	}

	private static String textOf(AgentMessage.ToolResultMessage t) {
		for (Content c : t.content()) if (c instanceof Content.Text) return ((Content.Text) c).text();
		return "";
	}

	private static String textOf(AgentToolResult<?> r) {
		if (r == null || r.content() == null) return "";
		for (Content c : r.content()) if (c instanceof Content.Text) return ((Content.Text) c).text();
		return "";
	}
}
