package com.duokanbook.pi.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
 * <p>Wire protocol (SSE over HTTP, {@code data: <json>} frames). The optional single space
 * after {@code data:} is accepted, and multiple data lines are joined until the blank event
 * delimiter:
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

	public ProxyStreamFn(String proxyUrl, String authToken) {
		this(proxyUrl, () -> authToken);
	}

	public ProxyStreamFn(String proxyUrl, Supplier<String> authTokenSupplier) {
		this.proxyUrl = proxyUrl;
		this.authTokenSupplier = authTokenSupplier;
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
		final HttpURLConnection[] connection = {null};

		// Abort handling: cancel the in-flight HTTP exchange / close the body.
		Runnable abortListener = () -> {
			if (holder[0] != null) {
				try { holder[0].close(); } catch (IOException ignored) {}
			}
			if (connection[0] != null) connection[0].disconnect();
		};
		if (options != null && options.signal() != null) {
			options.signal().addListener(abortListener);
		}

		try {
			if (options != null && options.signal() != null && options.signal().isAborted()) {
				throw new AbortSignal.AbortedException("Request aborted by user");
			}
			String body = buildRequestBody(model, context, options);
			HttpURLConnection request = (HttpURLConnection) new URL(proxyUrl + "/api/stream").openConnection();
			connection[0] = request;
			request.setRequestMethod("POST");
			request.setDoOutput(true);
			request.setRequestProperty("Authorization", "Bearer " + authTokenSupplier.get());
			request.setRequestProperty("Content-Type", "application/json");
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			request.setFixedLengthStreamingMode(bytes.length);
			OutputStream output = request.getOutputStream();
			try { output.write(bytes); } finally { output.close(); }
			int status = request.getResponseCode();
			String statusText = request.getResponseMessage();
			holder[0] = status / 100 == 2 ? request.getInputStream() : request.getErrorStream();
			if (status / 100 != 2) {
				String message = proxyErrorMessage(status, statusText, holder[0]);
				terminateWithError(stream, rc, options, message);
				return;
			}

			BufferedReader reader = new BufferedReader(new InputStreamReader(holder[0], StandardCharsets.UTF_8));
			String line;
			StringBuilder sseData = new StringBuilder();
			boolean terminalEvent = false;
			while ((line = reader.readLine()) != null) {
				if (options != null && options.signal() != null && options.signal().isAborted()) {
					throw new IOException("Request aborted by user");
				}
				if (line.isEmpty()) {
					if (sseData.length() > 0) {
						terminalEvent = dispatchSseData(sseData.toString(), rc, stream) || terminalEvent;
						sseData.setLength(0);
						if (terminalEvent) {
							stream.end(rc.partial);
							return;
						}
					}
					continue;
				}
				if (line.startsWith("data:")) {
					String data = line.substring(5);
					if (data.startsWith(" ")) data = data.substring(1);
					sseData.append(data).append('\n');
				}
			}
			if (sseData.length() > 0) terminalEvent = dispatchSseData(sseData.toString(), rc, stream) || terminalEvent;
			if (terminalEvent) stream.end(rc.partial);
			else terminateWithError(stream, rc, options, "Proxy stream ended without a terminal event");
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
			if (connection[0] != null) connection[0].disconnect();
		}
	}

	private static boolean dispatchSseData(String raw, Reconstructor rc,
			EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> stream) {
		String data = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
		if (data.trim().isEmpty()) return false;
		Object parsed = Json.parse(data);
		if (!(parsed instanceof Map<?, ?>)) return false;
		@SuppressWarnings("unchecked")
		Map<String, Object> event = (Map<String, Object>) parsed;
		AssistantMessageEvent ev = rc.process(event);
		if (ev != null) stream.push(ev);
		return ev != null && ("done".equals(ev.type()) || "error".equals(ev.type()));
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
			putIfNotNull(opts, "temperature", options.temperature());
			putIfNotNull(opts, "samplingParams", options.samplingParams());
			putIfNotNull(opts, "maxTokens", options.maxTokens());
			if (options.reasoning() != null) opts.put("reasoning", options.reasoning().name().toLowerCase());
			putIfNotNull(opts, "cacheRetention", options.cacheRetention());
			putIfNotNull(opts, "sessionId", options.sessionId());
			putIfNotNull(opts, "headers", options.headers());
			putIfNotNull(opts, "metadata", options.metadata());
			putIfNotNull(opts, "transport", options.transport());
			putIfNotNull(opts, "thinkingBudgets", options.thinkingBudgets());
			putIfNotNull(opts, "maxRetryDelayMs", options.maxRetryDelayMs());
		}
		root.put("options", opts);
		return Json.stringify(root);
	}

	private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
		if (value != null) target.put(key, value);
	}

	private static String proxyErrorMessage(int status, String statusText, InputStream body) {
		String fallback = "Proxy error: " + status
				+ (statusText != null && !statusText.isEmpty() ? " " + statusText : "");
		try {
			if (body == null) return fallback;
			Object parsed = Json.parse(new String(readAll(body), StandardCharsets.UTF_8));
			if (parsed instanceof Map<?, ?>) {
				Object errorValue = ((Map<?, ?>) parsed).get("error");
				if (errorValue instanceof String && !((String) errorValue).isEmpty()) return "Proxy error: " + errorValue;
			}
		} catch (IOException | IllegalArgumentException ignored) {
			// Keep the status-only fallback when the proxy does not return a JSON error body.
		}
		return fallback;
	}

	private static byte[] readAll(InputStream input) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int read;
		while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
		return output.toByteArray();
	}

	private static String messageOf(Throwable e) {
		Throwable c = e;
		while (c.getCause() != null && c.getCause() != c) c = c.getCause();
		return c.getMessage() != null ? c.getMessage() : c.toString();
	}

	// ───────────────────────── partial-message reconstruction ─────────────────────────

	/**
	 * Rebuilds the cumulative assistant message from bandwidth-optimized proxy events. Produces a
	 * fresh immutable {@link AgentMessage.AssistantMessage} snapshot per event, which the
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
			if (!(raw instanceof Map<?, ?>)) return Usage.empty();
			Map<?, ?> m = (Map<?, ?>) raw;
			Map<?, ?> cost = m.get("cost") instanceof Map<?, ?> ? (Map<?, ?>) m.get("cost") : null;
			return new Usage(
					longOf(m.get("input")), longOf(m.get("output")),
					longOf(m.get("cacheRead")), longOf(m.get("cacheWrite")),
					longOf(m.get("totalTokens")),
					new Usage.Cost(
							numOf(cost != null ? cost.get("input") : null),
							numOf(cost != null ? cost.get("output") : null),
							numOf(cost != null ? cost.get("cacheRead") : null),
							numOf(cost != null ? cost.get("cacheWrite") : null),
							numOf(cost != null ? cost.get("total") : null)));
		}

		private static long longOf(Object o) {
			return o instanceof Number ? ((Number) o).longValue() : 0L;
		}

		private static double numOf(Object o) {
			return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
		}
	}
}
