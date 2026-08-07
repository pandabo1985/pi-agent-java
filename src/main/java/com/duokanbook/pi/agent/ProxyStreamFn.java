package com.duokanbook.pi.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Stream function that proxies LLM calls through a server, equivalent to {@code proxy.ts}'s
 * {@code streamProxy}. The server authenticates and forwards to providers, and emits
 * <em>bandwidth-optimized</em> events (no cumulative {@code partial} field — only deltas keyed
 * by {@code contentIndex}). This client reconstructs the cumulative assistant message locally.
 *
 * <p>Wire protocol (SSE over HTTP, one {@code data: <json>} frame per line):
 * <pre>
 *   start | text_{start,delta,end} | thinking_{start,delta,end} |
 *   toolcall_{start,delta,end} | done | error
 * </pre>
 *
 * <p>Abort: registers a listener on the request signal that cancels the HTTP exchange and closes
 * the response stream. Failures are encoded as a terminal {@link AssistantMessageEvent.ErrorEvent}.
 */
public final class ProxyStreamFn implements StreamFn {

	private final String proxyUrl;
	private final Supplier<String> authTokenSupplier;
	private final HttpClient client;

	public ProxyStreamFn(String proxyUrl, String authToken) {
		this(proxyUrl, () -> authToken);
	}

	public ProxyStreamFn(String proxyUrl, Supplier<String> authTokenSupplier) {
		this.proxyUrl = proxyUrl;
		this.authTokenSupplier = authTokenSupplier;
		this.client = HttpClient.newHttpClient();
	}

	@Override
	public EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> stream(
			Model model, LlmContext context, SimpleStreamOptions options) {

		EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> stream = new EventStream<>();
		Thread t = new Thread(() -> run(model, context, options, stream), "pi-proxy-stream");
		t.setDaemon(true);
		t.start();
		return stream;
	}

	private void run(Model model, LlmContext context, SimpleStreamOptions options,
			EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> stream) {

		Reconstructor rc = new Reconstructor(model);
		final InputStream[] holder = {null};

		// Abort handling: cancel the in-flight HTTP exchange / close the body.
		Runnable abortListener = () -> {
			if (holder[0] != null) {
				try { holder[0].close(); } catch (IOException ignored) {}
			}
		};
		if (options != null && options.signal() != null) {
			options.signal().addListener(abortListener);
		}

		try {
			String body = buildRequestBody(model, context, options);
			HttpRequest req = HttpRequest.newBuilder(URI.create(proxyUrl + "/api/stream"))
					.header("Authorization", "Bearer " + authTokenSupplier.get())
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
					.build();

			HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
			if (resp.statusCode() / 100 != 2) {
				String message = "Proxy error: " + resp.statusCode() + " " + resp.statusCode();
				terminateWithError(stream, rc, options, message);
				return;
			}

			holder[0] = resp.body();
			BufferedReader reader = new BufferedReader(new InputStreamReader(holder[0], StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				if (options != null && options.signal() != null && options.signal().isAborted()) {
					throw new IOException("Request aborted by user");
				}
				if (!line.startsWith("data: ")) continue;
				String data = line.substring(6).trim();
				if (data.isEmpty()) continue;
				Object parsed = Json.parse(data);
				if (!(parsed instanceof Map<?, ?>)) continue;
				AssistantMessageEvent ev = rc.process(asMap(parsed));
				if (ev != null) stream.push(ev);
			}
			// Stream ended without a terminal event — finalize from the current partial.
			stream.end(rc.partial);
		} catch (Throwable e) {
			boolean aborted = options != null && options.signal() != null && options.signal().isAborted();
			String message = aborted ? "Request aborted by user" : messageOf(e);
			terminateWithError(stream, rc, options, message);
		} finally {
			if (options != null && options.signal() != null) {
				options.signal().removeListener(abortListener);
			}
			// Ensure the underlying HTTP response stream is always released, not just on abort.
			if (holder[0] != null) {
				try { holder[0].close(); } catch (IOException ignored) {}
			}
		}
	}

	private void terminateWithError(EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> stream,
			Reconstructor rc, SimpleStreamOptions options, String message) {
		boolean aborted = options != null && options.signal() != null && options.signal().isAborted();
		StopReason reason = aborted ? StopReason.ABORTED : StopReason.ERROR;
		AssistantMessageEvent ev = rc.error(reason, message);
		stream.push(ev);
		stream.end(ev.partialMessage());
	}

	private String buildRequestBody(Model model, LlmContext context, SimpleStreamOptions options) {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("model", MessageJson.toJson(model));

		Map<String, Object> ctx = new LinkedHashMap<>();
		ctx.put("systemPrompt", context.systemPrompt());
		List<Object> msgs = new ArrayList<>();
		for (AgentMessage m : context.messages()) msgs.add(MessageJson.toJson(m));
		ctx.put("messages", msgs);
		root.put("context", ctx);

		Map<String, Object> opts = new LinkedHashMap<>();
		if (options != null) {
			if (options.reasoning() != null) opts.put("reasoning", options.reasoning().name().toLowerCase());
			opts.put("sessionId", options.sessionId());
			opts.put("transport", options.transport());
			opts.put("thinkingBudgets", options.thinkingBudgets());
			opts.put("maxRetryDelayMs", options.maxRetryDelayMs());
		}
		root.put("options", opts);
		return Json.stringify(root);
	}

	private static String messageOf(Throwable e) {
		Throwable c = e;
		while (c.getCause() != null && c.getCause() != c) c = c.getCause();
		return c.getMessage() != null ? c.getMessage() : c.toString();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object o) {
		return (Map<String, Object>) o;
	}

	// ───────────────────────── partial-message reconstruction ─────────────────────────

	/**
	 * Rebuilds the cumulative assistant message from bandwidth-optimized proxy events. Produces a
	 * fresh {@link AgentMessage.AssistantMessage} snapshot per event (immutable records), which the
	 * loop stores into the transcript on each update.
	 */
	private static final class Reconstructor {
		private final Model model;
		private AgentMessage.AssistantMessage partial;
		private final Map<Integer, StringBuilder> partialJson = new LinkedHashMap<>();

		Reconstructor(Model model) {
			this.model = model;
			this.partial = new AgentMessage.AssistantMessage(
					new ArrayList<>(), model.api(), model.provider(), model.id(),
					Usage.empty(), StopReason.PENDING, null, System.currentTimeMillis());
		}

		private AgentMessage.AssistantMessage withContent(List<Content> content) {
			return new AgentMessage.AssistantMessage(
					new ArrayList<>(content), partial.api(), partial.provider(), partial.model(),
					partial.usage(), partial.stopReason(), partial.errorMessage(), partial.timestamp());
		}

		private AgentMessage.AssistantMessage terminal(StopReason reason, String errorMessage, Usage usage) {
			return new AgentMessage.AssistantMessage(
					new ArrayList<>(partial.content()), partial.api(), partial.provider(), partial.model(),
					usage != null ? usage : partial.usage(), reason, errorMessage, partial.timestamp());
		}

		@SuppressWarnings("unchecked")
		AssistantMessageEvent process(Map<String, Object> ev) {
			String type = String.valueOf(ev.get("type"));
			switch (type) {
				case "start":
					return new AssistantMessageEvent.Start(partial);
				case "text_start": {
					int idx = ((Number) ev.get("contentIndex")).intValue();
					return set(idx, new Content.Text(""), () -> new AssistantMessageEvent.TextStart(idx, partial));
				}
				case "text_delta": {
					int idx = ((Number) ev.get("contentIndex")).intValue();
					String delta = String.valueOf(ev.get("delta"));
					Content.Text old = textAt(idx);
					return set(idx, new Content.Text(old.text() + delta),
							() -> new AssistantMessageEvent.TextDelta(idx, delta, partial));
				}
				case "text_end": {
					int idx = ((Number) ev.get("contentIndex")).intValue();
					Content.Text old = textAt(idx);
					String sig = ev.get("contentSignature") != null ? String.valueOf(ev.get("contentSignature")) : null;
					return set(idx, new Content.Text(old.text(), sig),
							() -> new AssistantMessageEvent.TextEnd(idx, old.text(), partial));
				}
				case "thinking_start": {
					int idx = ((Number) ev.get("contentIndex")).intValue();
					return set(idx, new Content.Thinking(""), () -> new AssistantMessageEvent.ThinkingStart(idx, partial));
				}
				case "thinking_delta": {
					int idx = ((Number) ev.get("contentIndex")).intValue();
					String delta = String.valueOf(ev.get("delta"));
					Content.Thinking old = thinkingAt(idx);
					return set(idx, new Content.Thinking(old.thinking() + delta),
							() -> new AssistantMessageEvent.ThinkingDelta(idx, delta, partial));
				}
				case "thinking_end": {
					int idx = ((Number) ev.get("contentIndex")).intValue();
					Content.Thinking old = thinkingAt(idx);
					String sig = ev.get("contentSignature") != null ? String.valueOf(ev.get("contentSignature")) : null;
					return set(idx, new Content.Thinking(old.thinking(), sig),
							() -> new AssistantMessageEvent.ThinkingEnd(idx, old.thinking(), partial));
				}
				case "toolcall_start": {
					int idx = ((Number) ev.get("contentIndex")).intValue();
					String id = String.valueOf(ev.get("id"));
					String name = String.valueOf(ev.get("toolName"));
					partialJson.put(idx, new StringBuilder());
					return set(idx, new Content.ToolCall(id, name, new LinkedHashMap<>()),
							() -> new AssistantMessageEvent.ToolCallStart(idx, partial));
				}
				case "toolcall_delta": {
					int idx = ((Number) ev.get("contentIndex")).intValue();
					String delta = String.valueOf(ev.get("delta"));
					partialJson.get(idx).append(delta);
					Content.ToolCall old = toolCallAt(idx);
					Object args = Json.parseStreamingJson(partialJson.get(idx).toString());
					return set(idx, new Content.ToolCall(old.id(), old.name(), args),
							() -> new AssistantMessageEvent.ToolCallDelta(idx, delta, partial));
				}
				case "toolcall_end": {
					int idx = ((Number) ev.get("contentIndex")).intValue();
					Content.ToolCall old = toolCallAt(idx);
					Object args = Json.parseStreamingJson(partialJson.get(idx).toString());
					return set(idx, new Content.ToolCall(old.id(), old.name(), args),
							() -> new AssistantMessageEvent.ToolCallEnd(idx, partial));
				}
				case "done": {
					StopReason reason = StopReason.fromWire(String.valueOf(ev.get("reason")));
					Usage usage = usageOf(ev.get("usage"));
					partial = terminal(reason, null, usage);
					return new AssistantMessageEvent.Done(reason, partial);
				}
				case "error": {
					StopReason reason = StopReason.fromWire(String.valueOf(ev.get("reason")));
					String msg = ev.get("errorMessage") != null ? String.valueOf(ev.get("errorMessage")) : "Proxy stream error";
					Usage usage = usageOf(ev.get("usage"));
					partial = terminal(reason, msg, usage);
					return new AssistantMessageEvent.ErrorEvent(reason, msg, partial);
				}
				default:
					return null;
			}
		}

		AssistantMessageEvent error(StopReason reason, String message) {
			partial = terminal(reason, message, partial.usage());
			return new AssistantMessageEvent.ErrorEvent(reason, message, partial);
		}

		private interface Emitter {
			AssistantMessageEvent emit();
		}

		private AssistantMessageEvent set(int idx, Content replacement, Emitter emitter) {
			List<Content> content = new ArrayList<>(partial.content());
			while (content.size() <= idx) content.add(null);
			content.set(idx, replacement);
			partial = withContent(content);
			return emitter.emit();
		}

		private Content.Text textAt(int idx) {
			return (Content.Text) partial.content().get(idx);
		}

		private Content.Thinking thinkingAt(int idx) {
			return (Content.Thinking) partial.content().get(idx);
		}

		private Content.ToolCall toolCallAt(int idx) {
			return (Content.ToolCall) partial.content().get(idx);
		}

		private Usage usageOf(Object raw) {
			if (!(raw instanceof Map<?, ?> m)) return Usage.empty();
			return new Usage(
					longOf(m.get("input")), longOf(m.get("output")),
					longOf(m.get("cacheRead")), longOf(m.get("cacheWrite")),
					longOf(m.get("totalTokens")),
					new Usage.Cost(numOf(m.get("cost") != null ? ((Map<?, ?>) m.get("cost")).get("total") : null), 0, 0, 0,
							numOf(m.get("cost") != null ? ((Map<?, ?>) m.get("cost")).get("total") : null)));
		}

		private static long longOf(Object o) {
			return o instanceof Number n ? n.longValue() : 0L;
		}

		private static double numOf(Object o) {
			return o instanceof Number n ? n.doubleValue() : 0.0;
		}
	}
}
