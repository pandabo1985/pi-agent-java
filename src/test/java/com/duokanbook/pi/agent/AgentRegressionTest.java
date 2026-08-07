package com.duokanbook.pi.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
	public void convertedRecordsRetainValueSemantics() {
		Content.Text first = new Content.Text("hello");
		Content.Text second = new Content.Text("hello");
		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
		assertEquals(new AgentEvent.AgentStart(), new AgentEvent.AgentStart());
		assertEquals(new Usage.Cost(1, 2, 3, 4, 10), new Usage.Cost(1, 2, 3, 4, 10));
		assertEquals("key", new SimpleStreamOptions("key", null, null, null, "auto", null, null).apiKey());
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
