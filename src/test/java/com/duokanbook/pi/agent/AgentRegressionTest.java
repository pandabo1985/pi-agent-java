package com.duokanbook.pi.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class AgentRegressionTest {

	@Test
	public void coercesNestedArgumentsWithoutMutatingTheToolCallValue() {
		Map<String, Object> count = schema("integer");
		Map<String, Object> item = objectSchema(Collections.<String, Object>singletonMap("count", count), Collections.singletonList("count"), false);
		Map<String, Object> root = objectSchema(Collections.<String, Object>singletonMap("items", arraySchema(item)), Collections.singletonList("items"), false);
		Map<String, Object> input = new LinkedHashMap<String, Object>();
		input.put("items", Collections.<Object>singletonList(Collections.<String, Object>singletonMap("count", "2")));

		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) JsonSchemaValidator.coerceAndValidate("edit", input, root);
		@SuppressWarnings("unchecked")
		Map<String, Object> resultItem = (Map<String, Object>) ((List<?>) result.get("items")).get(0);
		assertEquals(Long.valueOf(2), resultItem.get("count"));
		assertEquals("2", ((Map<?, ?>) ((List<?>) input.get("items")).get(0)).get("count"));

		input.put("extra", Boolean.TRUE);
		try {
			JsonSchemaValidator.coerceAndValidate("edit", input, root);
			throw new AssertionError("expected additional-property validation to fail");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("additional property"));
		}
	}

	@Test
	public void rejectsJavaOnlyNumericSuffixesDuringSchemaCoercion() {
		Map<String, Object> input = new LinkedHashMap<String, Object>();
		input.put("count", "1d");
		try {
			JsonSchemaValidator.coerceAndValidate("edit", input, objectSchema(
					Collections.<String, Object>singletonMap("count", schema("number")),
					Collections.singletonList("count"), false));
			throw new AssertionError("expected invalid numeric suffix to fail validation");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("expected number"));
		}
	}

	@Test
	public void proxyUsesJsonErrorBodyForNonSuccessResponses() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/stream", exchange -> {
			byte[] response = "{\"error\":\"denied\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(403, response.length);
			OutputStream output = exchange.getResponseBody();
			try { output.write(response); } finally { output.close(); }
		});
		server.start();
		try {
			Model model = Model.unknown();
			ProxyStreamFn proxy = new ProxyStreamFn("http://127.0.0.1:" + server.getAddress().getPort(), "token");
			EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> stream = proxy.stream(model,
					new LlmContext("", Collections.<AgentMessage>emptyList(), Collections.<AgentTool>emptyList()),
					SimpleStreamOptions.builder().build());
			AgentMessage.AssistantMessage result = stream.result().get(5, TimeUnit.SECONDS);
			assertEquals(StopReason.ERROR, result.stopReason());
			assertEquals("Proxy error: denied", result.errorMessage());
		} finally {
			server.stop(0);
		}
	}

	@Test
	public void proxyIncludesStatusTextForNonJsonErrors() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/stream", exchange -> {
			byte[] response = "unavailable".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(403, response.length);
			OutputStream output = exchange.getResponseBody();
			try { output.write(response); } finally { output.close(); }
		});
		server.start();
		try {
			ProxyStreamFn proxy = new ProxyStreamFn("http://127.0.0.1:" + server.getAddress().getPort(), "token");
			AgentMessage.AssistantMessage result = proxy.stream(Model.unknown(),
					new LlmContext("", Collections.<AgentMessage>emptyList(), Collections.<AgentTool>emptyList()),
					SimpleStreamOptions.builder().build()).result().get(5, TimeUnit.SECONDS);
			assertEquals("Proxy error: 403 Forbidden", result.errorMessage());
		} finally {
			server.stop(0);
		}
	}

	@Test
	public void proxyPreservesUsageCostBreakdown() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/stream", exchange -> {
			byte[] response = ("data: {\"type\":\"done\",\"reason\":\"stop\",\"usage\":{"
					+ "\"input\":1,\"output\":2,\"cacheRead\":3,\"cacheWrite\":4,\"totalTokens\":10,"
					+ "\"cost\":{\"input\":0.1,\"output\":0.2,\"cacheRead\":0.3,\"cacheWrite\":0.4,\"total\":1.0}}}\n\n")
					.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, 0);
			OutputStream output = exchange.getResponseBody();
			try { output.write(response); } finally { output.close(); }
		});
		server.start();
		try {
			ProxyStreamFn proxy = new ProxyStreamFn("http://127.0.0.1:" + server.getAddress().getPort(), "token");
			AgentMessage.AssistantMessage result = proxy.stream(Model.unknown(),
					new LlmContext("", Collections.<AgentMessage>emptyList(), Collections.<AgentTool>emptyList()),
					SimpleStreamOptions.builder().build()).result().get(5, TimeUnit.SECONDS);
			assertEquals(10L, result.usage().totalTokens());
			assertEquals(0.1, result.usage().cost().input(), 0.0);
			assertEquals(0.2, result.usage().cost().output(), 0.0);
			assertEquals(0.3, result.usage().cost().cacheRead(), 0.0);
			assertEquals(0.4, result.usage().cost().cacheWrite(), 0.0);
			assertEquals(1.0, result.usage().cost().total(), 0.0);
		} finally {
			server.stop(0);
		}
	}


	@Test
	public void proxyForwardsLegacyToolDeclarationsInTheRequestContext() throws Exception {
		AtomicReference<String> requestBody = new AtomicReference<String>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/stream", exchange -> {
			InputStream input = exchange.getRequestBody();
			ByteArrayOutputStream captured = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			int read;
			while ((read = input.read(buffer)) != -1) captured.write(buffer, 0, read);
			requestBody.set(new String(captured.toByteArray(), StandardCharsets.UTF_8));
			byte[] response = "data:{\"type\":\"done\",\"reason\":\"stop\"}\n\n".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, 0);
			OutputStream output = exchange.getResponseBody();
			try { output.write(response); } finally { output.close(); }
		});
		server.start();
		try {
			AgentTool echo = tool("echo", signal -> CompletableFuture.<AgentToolResult<?>>completedFuture(toolText("ok", false)));
			ProxyStreamFn proxy = new ProxyStreamFn("http://127.0.0.1:" + server.getAddress().getPort(), "token");
			proxy.stream(Model.unknown(),
					new LlmContext("system", Collections.<AgentMessage>emptyList(), Collections.singletonList(echo)),
					SimpleStreamOptions.builder().build()).result().get(5, TimeUnit.SECONDS);

			@SuppressWarnings("unchecked")
			Map<String, Object> root = (Map<String, Object>) Json.parse(requestBody.get());
			@SuppressWarnings("unchecked")
			Map<String, Object> context = (Map<String, Object>) root.get("context");
			List<?> tools = (List<?>) context.get("tools");
			assertEquals(1, tools.size());
			@SuppressWarnings("unchecked")
			Map<String, Object> declaration = (Map<String, Object>) tools.get(0);
			assertEquals("echo", declaration.get("name"));
			assertEquals("echo", declaration.get("description"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	public void convertedRecordsRetainValueSemantics() {
		Content.Text first = new Content.Text("hello");
		Content.Text second = new Content.Text("hello");
		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
		assertEquals(new AgentEvent.AgentStart(), new AgentEvent.AgentStart());
		assertEquals(new Usage.Cost(1, 2, 3, 4, 10), new Usage.Cost(1, 2, 3, 4, 10));
		assertEquals("key", new SimpleStreamOptions("key", null, null, null, "auto", null, null).apiKey());
	}

	@Test
	public void parallelBatchEmitsToolEndsInCompletionOrderButToolResultsInSourceOrder() throws Exception {
		CountDownLatch fastFinished = new CountDownLatch(1);
		AtomicInteger streamCalls = new AtomicInteger();
		List<String> executionEnds = Collections.synchronizedList(new ArrayList<String>());
		List<String> resultMessages = Collections.synchronizedList(new ArrayList<String>());

		AgentTool slow = tool("slow", signal -> {
			CompletableFuture<AgentToolResult<?>> future = new CompletableFuture<>();
			Thread worker = new Thread(() -> {
				await(fastFinished);
				future.complete(toolText("slow", true));
			}, "test-slow-tool");
			worker.setDaemon(true);
			worker.start();
			return future;
		});
		AgentTool fast = tool("fast", signal -> {
			fastFinished.countDown();
			return CompletableFuture.<AgentToolResult<?>>completedFuture(toolText("fast", true));
		});

		AgentLoopConfig config = loopConfig();
		StreamFn stream = (model, context, options) -> {
			streamCalls.incrementAndGet();
			return assistantStream(assistant(Arrays.<Content>asList(
					new Content.ToolCall("slow-id", "slow", Collections.<String, Object>emptyMap()),
					new Content.ToolCall("fast-id", "fast", Collections.<String, Object>emptyMap())),
					StopReason.TOOL_USE, null));
		};

		AgentLoop.runAgentLoop(Collections.<AgentMessage>singletonList(new AgentMessage.UserMessage("go")),
				new AgentContext("", new ArrayList<AgentMessage>(), Arrays.asList(slow, fast)), config, event -> {
					if (event instanceof AgentEvent.ToolExecutionEnd) {
						executionEnds.add(((AgentEvent.ToolExecutionEnd) event).toolCallId());
					} else if (event instanceof AgentEvent.MessageEnd
							&& ((AgentEvent.MessageEnd) event).message() instanceof AgentMessage.ToolResultMessage) {
						resultMessages.add(((AgentMessage.ToolResultMessage) ((AgentEvent.MessageEnd) event).message()).toolCallId());
					}
				}, new AbortSignal(), stream);

		assertEquals(1, streamCalls.get());
		assertEquals(Arrays.asList("fast-id", "slow-id"), executionEnds);
		assertEquals(Arrays.asList("slow-id", "fast-id"), resultMessages);
	}

	@Test
	public void lengthTruncationDoesNotExecutePossiblyIncompleteToolCalls() throws Exception {
		AtomicInteger executions = new AtomicInteger();
		AgentLoopConfig config = loopConfig();
		config.shouldStopAfterTurn = context -> CompletableFuture.completedFuture(Boolean.TRUE);
		AgentTool tool = tool("write", signal -> {
			executions.incrementAndGet();
			return CompletableFuture.<AgentToolResult<?>>completedFuture(toolText("should not run", false));
		});

		List<AgentMessage> messages = AgentLoop.runAgentLoop(
				Collections.<AgentMessage>singletonList(new AgentMessage.UserMessage("go")),
				new AgentContext("", new ArrayList<AgentMessage>(), Collections.singletonList(tool)), config, event -> {},
				new AbortSignal(), (model, context, options) -> assistantStream(assistant(
						Collections.<Content>singletonList(new Content.ToolCall("cut", "write", Collections.<String, Object>emptyMap())),
						StopReason.LENGTH, null)));

		assertEquals(0, executions.get());
		AgentMessage.ToolResultMessage result = (AgentMessage.ToolResultMessage) messages.get(2);
		assertTrue(result.isError());
		assertTrue(textOf(result.content()).contains("output token limit"));
	}

	@Test
	public void maxTurnsStopsRepeatedToolUseBeforeAnotherModelRequest() throws Exception {
		AtomicInteger streamCalls = new AtomicInteger();
		AgentLoopConfig config = loopConfig();
		config.maxTurns = 1;
		AgentTool tool = tool("again", signal -> CompletableFuture.<AgentToolResult<?>>completedFuture(toolText("ok", false)));

		List<AgentMessage> messages = AgentLoop.runAgentLoop(
				Collections.<AgentMessage>singletonList(new AgentMessage.UserMessage("go")),
				new AgentContext("", new ArrayList<AgentMessage>(), Collections.singletonList(tool)), config, event -> {},
				new AbortSignal(), (model, context, options) -> {
					streamCalls.incrementAndGet();
					return assistantStream(assistant(Collections.<Content>singletonList(
							new Content.ToolCall("again-id", "again", Collections.<String, Object>emptyMap())), StopReason.TOOL_USE, null));
				});

		assertEquals(1, streamCalls.get());
		AgentMessage.AssistantMessage failure = (AgentMessage.AssistantMessage) messages.get(3);
		assertEquals(StopReason.ERROR, failure.stopReason());
		assertEquals("Maximum assistant turns exceeded: 1", failure.errorMessage());
	}

	@Test
	public void toolTimeoutCancelsTheFutureAndProducesAnErrorResult() throws Exception {
		CompletableFuture<AgentToolResult<?>> neverCompletes = new CompletableFuture<>();
		AgentLoopConfig config = loopConfig();
		config.toolTimeoutMs = 30L;
		config.shouldStopAfterTurn = context -> CompletableFuture.completedFuture(Boolean.TRUE);
		AgentTool tool = tool("slow", signal -> neverCompletes);

		List<AgentMessage> messages = AgentLoop.runAgentLoop(
				Collections.<AgentMessage>singletonList(new AgentMessage.UserMessage("go")),
				new AgentContext("", new ArrayList<AgentMessage>(), Collections.singletonList(tool)), config, event -> {},
				new AbortSignal(), (model, context, options) -> assistantStream(assistant(Collections.<Content>singletonList(
						new Content.ToolCall("slow-id", "slow", Collections.<String, Object>emptyMap())), StopReason.TOOL_USE, null)));

		assertTrue(neverCompletes.isCancelled());
		AgentMessage.ToolResultMessage result = (AgentMessage.ToolResultMessage) messages.get(2);
		assertTrue(result.isError());
		assertTrue(textOf(result.content()).contains("timed out after 30 ms"));
	}

	@Test
	public void abortDuringToolExecutionCancelsTheToolAndEndsWithoutAResultMessage() throws Exception {
		CountDownLatch toolStarted = new CountDownLatch(1);
		CompletableFuture<AgentToolResult<?>> neverCompletes = new CompletableFuture<>();
		AtomicInteger streamCalls = new AtomicInteger();
		AgentTool tool = tool("slow", signal -> {
			toolStarted.countDown();
			return neverCompletes;
		});
		Agent.AgentOptions options = new Agent.AgentOptions();
		options.tools = Collections.singletonList(tool);
		options.streamFn = (model, context, streamOptions) -> {
			streamCalls.incrementAndGet();
			return assistantStream(assistant(Collections.<Content>singletonList(
					new Content.ToolCall("slow-id", "slow", Collections.<String, Object>emptyMap())), StopReason.TOOL_USE, null));
		};
		Agent agent = new Agent(options);

		CompletableFuture<Void> run = agent.prompt("go");
		assertTrue(toolStarted.await(2, TimeUnit.SECONDS));
		agent.abort();
		run.get(2, TimeUnit.SECONDS);

		assertTrue(neverCompletes.isCancelled());
		assertEquals(1, streamCalls.get());
		for (AgentMessage message : agent.state().messages()) {
			assertTrue("abort must not synthesize a tool result", !(message instanceof AgentMessage.ToolResultMessage));
		}
		AgentMessage.AssistantMessage failure = (AgentMessage.AssistantMessage) agent.state().messages().get(2);
		assertEquals(StopReason.ABORTED, failure.stopReason());
	}

	@Test
	public void abortSettlesTheAgentAndPublishedMessageSnapshotsStayStable() throws Exception {
		CountDownLatch streamStarted = new CountDownLatch(1);
		Agent.AgentOptions options = new Agent.AgentOptions();
		options.streamFn = (model, context, streamOptions) -> {
			EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> stream = new EventStream<>();
			streamOptions.signal().addListener(() -> {
				AgentMessage.AssistantMessage aborted = assistant(Collections.<Content>singletonList(new Content.Text("cancelled")),
						StopReason.ABORTED, "cancelled");
				stream.push(new AssistantMessageEvent.ErrorEvent(StopReason.ABORTED, "cancelled", aborted));
				stream.end(aborted);
			});
			streamStarted.countDown();
			return stream;
		};
		Agent agent = new Agent(options);

		CompletableFuture<Void> run = agent.prompt("wait");
		assertTrue(streamStarted.await(2, TimeUnit.SECONDS));
		List<AgentMessage> snapshot = agent.state().messages();
		assertEquals(1, snapshot.size());
		try {
			snapshot.add(new AgentMessage.UserMessage("mutate"));
			fail("state messages must be immutable snapshots");
		} catch (UnsupportedOperationException expected) {
			// Expected.
		}

		agent.abort();
		run.get(2, TimeUnit.SECONDS);
		agent.waitForIdle().get(2, TimeUnit.SECONDS);
		for (AgentMessage ignored : snapshot) {
			// Iterating an earlier snapshot must not race with later publications.
		}
		assertEquals(1, snapshot.size());
		assertEquals(2, agent.state().messages().size());
		AgentMessage.AssistantMessage finalMessage = (AgentMessage.AssistantMessage) agent.state().messages().get(1);
		assertEquals(StopReason.ABORTED, finalMessage.stopReason());
		assertEquals("cancelled", agent.state().errorMessage);
	}

	@Test
	public void steeringAndFollowUpsAreInjectedAndCustomMessagesStayOutOfLlmContext() throws Exception {
		CountDownLatch firstRequest = new CountDownLatch(1);
		CountDownLatch releaseFirstResponse = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		List<List<String>> visibleRoles = new CopyOnWriteArrayList<>();
		Agent.AgentOptions options = new Agent.AgentOptions();
		options.messages = Collections.<AgentMessage>singletonList(new AgentMessage.CustomMessage(
				"internal", Collections.<String, Object>singletonMap("source", "test"), 1L));
		options.streamFn = (model, context, streamOptions) -> {
			List<String> roles = new ArrayList<>();
			for (AgentMessage message : context.messages()) roles.add(message.role());
			visibleRoles.add(roles);
			int call = calls.incrementAndGet();
			AgentMessage.AssistantMessage response = assistant(Collections.<Content>singletonList(
					new Content.Text("response " + call)), StopReason.STOP, null);
			if (call != 1) return assistantStream(response);

			EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> stream = new EventStream<>();
			stream.push(new AssistantMessageEvent.Start(response));
			Thread worker = new Thread(() -> {
				await(releaseFirstResponse);
				stream.push(new AssistantMessageEvent.Done(StopReason.STOP, response));
				stream.end(response);
			}, "test-first-response");
			worker.setDaemon(true);
			worker.start();
			firstRequest.countDown();
			return stream;
		};
		Agent agent = new Agent(options);

		CompletableFuture<Void> run = agent.prompt("original");
		assertTrue(firstRequest.await(2, TimeUnit.SECONDS));
		agent.steer(new AgentMessage.UserMessage("steered"));
		agent.followUp(new AgentMessage.UserMessage("follow-up"));
		releaseFirstResponse.countDown();
		run.get(2, TimeUnit.SECONDS);

		assertEquals(3, calls.get());
		assertEquals(Collections.singletonList("user"), visibleRoles.get(0));
		assertEquals(Arrays.asList("user", "assistant", "user"), visibleRoles.get(1));
		assertEquals(Arrays.asList("user", "assistant", "user", "assistant", "user"), visibleRoles.get(2));
	}

	@Test
	public void agentFailureIncludesAllNewMessagesAndErrorTextInTheTerminalPayload() throws Exception {
		List<List<AgentMessage>> agentEnds = new CopyOnWriteArrayList<>();
		Agent.AgentOptions options = new Agent.AgentOptions();
		options.streamFn = (model, context, streamOptions) -> {
			throw new IllegalStateException("provider exploded");
		};
		Agent agent = new Agent(options);
		agent.subscribe((event, signal) -> {
			if (event instanceof AgentEvent.AgentEnd) {
				agentEnds.add(new ArrayList<>(((AgentEvent.AgentEnd) event).messages()));
			}
		});

		agent.prompt("hello").get(2, TimeUnit.SECONDS);

		assertEquals(1, agentEnds.size());
		assertEquals(2, agentEnds.get(0).size());
		AgentMessage.AssistantMessage failure = (AgentMessage.AssistantMessage) agentEnds.get(0).get(1);
		assertEquals(StopReason.ERROR, failure.stopReason());
		assertEquals("provider exploded", failure.errorMessage());
		assertEquals("provider exploded", textOf(failure.content()));
	}

	@Test
	public void proxyAcceptsDataFramesWithoutSpacesAndWithMultilineJson() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/stream", exchange -> {
			byte[] response = ("event: message\n"
					+ "id: 1\n"
					+ "data:{\"type\":\"start\"}\n\n"
					+ "data:{\"type\":\"done\",\"reason\":\n"
					+ "data:\"stop\"}\n\n").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, 0);
			OutputStream output = exchange.getResponseBody();
			try { output.write(response); } finally { output.close(); }
		});
		server.start();
		try {
			ProxyStreamFn proxy = new ProxyStreamFn("http://127.0.0.1:" + server.getAddress().getPort(), "token");
			AgentMessage.AssistantMessage result = proxy.stream(Model.unknown(),
					new LlmContext("", Collections.<AgentMessage>emptyList(), Collections.<AgentTool>emptyList()),
					SimpleStreamOptions.builder().build()).result().get(5, TimeUnit.SECONDS);
			assertEquals(StopReason.STOP, result.stopReason());
		} finally {
			server.stop(0);
		}
	}

	@Test
	public void proxyDoesNotOpenARequestWhenTheSignalIsAlreadyAborted() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/stream", exchange -> requests.incrementAndGet());
		server.start();
		try {
			AbortSignal signal = new AbortSignal();
			signal.abort();
			ProxyStreamFn proxy = new ProxyStreamFn("http://127.0.0.1:" + server.getAddress().getPort(), "token");
			AgentMessage.AssistantMessage result = proxy.stream(Model.unknown(),
					new LlmContext("", Collections.<AgentMessage>emptyList(), Collections.<AgentTool>emptyList()),
					SimpleStreamOptions.builder().signal(signal).build()).result().get(2, TimeUnit.SECONDS);
			assertEquals(StopReason.ABORTED, result.stopReason());
			assertEquals(0, requests.get());
		} finally {
			server.stop(0);
		}
	}

	@Test
	public void jsonRejectsInvalidNumberForms() {
		assertEquals(Double.valueOf(-1200.5), Json.parse("-12.005e2"));
		for (String invalid : Arrays.asList("+1", "01", "1.", "1e", "-")) {
			try {
				Json.parse(invalid);
				fail("expected invalid JSON number: " + invalid);
			} catch (IllegalArgumentException expected) {
				// Expected.
			}
		}
	}


	@Test
	public void prepareNextTurnRunsOnlyWhenAnotherProviderTurnIsSelected() throws Exception {
		AtomicInteger prepares = new AtomicInteger();
		AgentLoopConfig config = loopConfig();
		config.prepareNextTurn = context -> {
			prepares.incrementAndGet();
			return CompletableFuture.completedFuture(null);
		};

		AgentLoop.runAgentLoop(
				Collections.<AgentMessage>singletonList(new AgentMessage.UserMessage("done")),
				new AgentContext("", new ArrayList<AgentMessage>(), Collections.<AgentTool>emptyList()),
				config, event -> {}, new AbortSignal(),
				(model, context, options) -> assistantStream(assistant(
						Collections.<Content>singletonList(new Content.Text("ok")), StopReason.STOP, null)));

		assertEquals("terminal turns must not prepare a nonexistent next turn", 0, prepares.get());
	}

	@Test
	public void finishTurnRunsBeforeTurnEndAndCanForceExactlyOneContinuation() throws Exception {
		AtomicInteger streamCalls = new AtomicInteger();
		AtomicInteger finishCalls = new AtomicInteger();
		List<String> ordering = new ArrayList<String>();
		AgentLoopConfig config = loopConfig();
		config.finishTurn = (context, signal) -> {
			ordering.add("finish-" + finishCalls.incrementAndGet());
			return CompletableFuture.completedFuture(
					finishCalls.get() == 1 ? AgentLoopConfig.TurnDecision.continueRun() : null);
		};

		AgentLoop.runAgentLoop(
				Collections.<AgentMessage>singletonList(new AgentMessage.UserMessage("continue once")),
				new AgentContext("", new ArrayList<AgentMessage>(), Collections.<AgentTool>emptyList()),
				config,
				event -> {
					if (event instanceof AgentEvent.TurnEnd) ordering.add("turn-end-" + finishCalls.get());
				},
				new AbortSignal(),
				(model, context, options) -> {
					int call = streamCalls.incrementAndGet();
					return assistantStream(assistant(
							Collections.<Content>singletonList(new Content.Text("response " + call)),
							StopReason.STOP, null));
				});

		assertEquals(2, streamCalls.get());
		assertEquals(Arrays.asList("finish-1", "turn-end-1", "finish-2", "turn-end-2"), ordering);
	}

	@Test
	public void prepareRequestRunsBeforeTheFirstProviderRequest() throws Exception {
		List<String> ordering = new ArrayList<String>();
		AgentLoopConfig config = loopConfig();
		config.prepareRequest = (context, signal) -> {
			ordering.add("prepare");
			assertEquals(ThinkingLevel.OFF, context.thinkingLevel());
			return CompletableFuture.completedFuture(null);
		};

		AgentLoop.runAgentLoop(
				Collections.<AgentMessage>singletonList(new AgentMessage.UserMessage("hello")),
				new AgentContext("", new ArrayList<AgentMessage>(), Collections.<AgentTool>emptyList()),
				config, event -> {}, new AbortSignal(),
				(model, context, options) -> {
					ordering.add("stream");
					return assistantStream(assistant(
							Collections.<Content>singletonList(new Content.Text("ok")), StopReason.STOP, null));
				});

		assertEquals(Arrays.asList("prepare", "stream"), ordering);
	}

	@Test
	public void finishTurnEndLeavesQueuedMessagesUntouched() throws Exception {
		AtomicInteger streamCalls = new AtomicInteger();
		Agent.AgentOptions options = new Agent.AgentOptions();
		options.finishTurn = (context, signal) -> CompletableFuture.completedFuture(AgentLoopConfig.TurnDecision.end());
		options.streamFn = (model, context, streamOptions) -> {
			streamCalls.incrementAndGet();
			return assistantStream(assistant(
					Collections.<Content>singletonList(new Content.Text("done")), StopReason.STOP, null));
		};
		Agent agent = new Agent(options);
		agent.subscribe((event, signal) -> {
			if (event instanceof AgentEvent.MessageEnd
					&& ((AgentEvent.MessageEnd) event).message() instanceof AgentMessage.AssistantMessage) {
				agent.steer(new AgentMessage.UserMessage("steer later"));
				agent.followUp(new AgentMessage.UserMessage("follow later"));
			}
		});

		agent.prompt("go").get(2, TimeUnit.SECONDS);

		assertEquals(1, streamCalls.get());
		assertTrue(agent.hasQueuedMessages());
		assertEquals("steer later",
				((Content.Text) ((AgentMessage.UserMessage) agent.peekQueuedMessages().get(0)).content().get(0)).text());
	}

	@Test
	public void peekQueuedMessagesDoesNotConsumeAndPrefersSteering() {
		Agent agent = new Agent(new Agent.AgentOptions());
		agent.followUp(new AgentMessage.UserMessage("follow"));
		agent.steer(new AgentMessage.UserMessage("steer"));

		List<AgentMessage> first = agent.peekQueuedMessages();
		List<AgentMessage> second = agent.peekQueuedMessages();
		assertEquals(first, second);
		assertEquals("steer",
				((Content.Text) ((AgentMessage.UserMessage) first.get(0)).content().get(0)).text());

		agent.clearSteeringQueue();
		assertEquals("follow",
				((Content.Text) ((AgentMessage.UserMessage) agent.peekQueuedMessages().get(0)).content().get(0)).text());
	}

	private static AgentLoopConfig loopConfig() {
		AgentLoopConfig config = new AgentLoopConfig(Model.unknown(),
				messages -> CompletableFuture.completedFuture(messages));
		config.toolExecution = ToolExecutionMode.PARALLEL;
		return config;
	}

	private static AgentTool tool(final String name, final ToolExecution execution) {
		return new AgentTool() {
			@Override public String name() { return name; }
			@Override public String label() { return name; }
			@Override public String description() { return name; }
			@Override public Map<String, Object> parameters() { return null; }
			@Override public CompletableFuture<AgentToolResult<?>> execute(
					String toolCallId, Object args, AbortSignal signal,
					java.util.function.Consumer<AgentToolResult<?>> onUpdate) {
				return execution.execute(signal);
			}
		};
	}

	private static AgentToolResult<Object> toolText(String text, boolean terminate) {
		return new AgentToolResult<Object>(Collections.<Content>singletonList(new Content.Text(text)), null,
				null, null, terminate);
	}

	private static AgentMessage.AssistantMessage assistant(List<Content> content, StopReason reason, String errorMessage) {
		return new AgentMessage.AssistantMessage(new ArrayList<>(content), "test", "test", "test", Usage.empty(),
				reason, errorMessage, 1L);
	}

	private static EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> assistantStream(
			AgentMessage.AssistantMessage message) {
		EventStream<AssistantMessageEvent, AgentMessage.AssistantMessage> stream = new EventStream<>();
		stream.push(new AssistantMessageEvent.Start(message));
		if (message.stopReason() == StopReason.ERROR || message.stopReason() == StopReason.ABORTED) {
			stream.push(new AssistantMessageEvent.ErrorEvent(message.stopReason(), message.errorMessage(), message));
		} else {
			stream.push(new AssistantMessageEvent.Done(message.stopReason(), message));
		}
		stream.end(message);
		return stream;
	}

	private static String textOf(List<Content> content) {
		for (Content block : content) {
			if (block instanceof Content.Text) return ((Content.Text) block).text();
		}
		return "";
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for test coordination");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("interrupted while waiting for test coordination", e);
		}
	}

	@FunctionalInterface
	private interface ToolExecution {
		CompletableFuture<AgentToolResult<?>> execute(AbortSignal signal);
	}

	private static Map<String, Object> schema(String type) {
		Map<String, Object> schema = new LinkedHashMap<String, Object>();
		schema.put("type", type);
		return schema;
	}

	private static Map<String, Object> arraySchema(Map<String, Object> items) {
		Map<String, Object> schema = schema("array");
		schema.put("items", items);
		return schema;
	}

	private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required, boolean additional) {
		Map<String, Object> schema = schema("object");
		schema.put("properties", properties);
		schema.put("required", required);
		schema.put("additionalProperties", Boolean.valueOf(additional));
		return schema;
	}
}
