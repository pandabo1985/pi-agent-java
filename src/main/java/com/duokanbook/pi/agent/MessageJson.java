package com.duokanbook.pi.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes agent messages, content blocks, models, and usage into plain JSON values
 * ({@code Map/List/String/Number/Boolean/null}) ready for {@link Json#stringify(Object)}.
 *
 * <p>Used by the proxy stream function to build request bodies. Round-trips with the wire
 * format the TypeScript proxy expects.
 */
public final class MessageJson {

	private MessageJson() {}

	public static Map<String, Object> toJson(Model m) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("id", m.id());
		o.put("name", m.name());
		o.put("api", m.api());
		o.put("provider", m.provider());
		o.put("baseUrl", m.baseUrl());
		o.put("reasoning", m.reasoning());
		o.put("contextWindow", m.contextWindow());
		o.put("maxTokens", m.maxTokens());
		if (m.cost() != null) {
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("input", m.cost().input());
			c.put("output", m.cost().output());
			c.put("cacheRead", m.cost().cacheRead());
			c.put("cacheWrite", m.cost().cacheWrite());
			o.put("cost", c);
		}
		return o;
	}

	/** Serialize the provider-visible declaration of an executable agent tool. */
	public static Map<String, Object> toJson(AgentTool tool) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("name", tool.name());
		o.put("description", tool.description());
		o.put("parameters", tool.parameters() != null ? tool.parameters() : new LinkedHashMap<String, Object>());
		return o;
	}

	public static Map<String, Object> toJson(Usage u) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("input", u.input());
		o.put("output", u.output());
		o.put("cacheRead", u.cacheRead());
		o.put("cacheWrite", u.cacheWrite());
		o.put("totalTokens", u.totalTokens());
		if (u.cost() != null) {
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("input", u.cost().input());
			c.put("output", u.cost().output());
			c.put("cacheRead", u.cost().cacheRead());
			c.put("cacheWrite", u.cost().cacheWrite());
			c.put("total", u.cost().total());
			o.put("cost", c);
		}
		return o;
	}

	public static Object toJson(Content c) {
		Map<String, Object> o = new LinkedHashMap<>();
		switch (c.type()) {
			case "text": {
				Content.Text t = (Content.Text) c;
				o.put("type", "text");
				o.put("text", t.text());
				if (t.textSignature() != null) o.put("textSignature", t.textSignature());
				break;
			}
			case "image": {
				Content.Image i = (Content.Image) c;
				o.put("type", "image");
				o.put("data", i.data());
				o.put("mimeType", i.mimeType());
				break;
			}
			case "thinking": {
				Content.Thinking t = (Content.Thinking) c;
				o.put("type", "thinking");
				o.put("thinking", t.thinking());
				if (t.thinkingSignature() != null) o.put("thinkingSignature", t.thinkingSignature());
				break;
			}
			case "toolCall": {
				Content.ToolCall tc = (Content.ToolCall) c;
				o.put("type", "toolCall");
				o.put("id", tc.id());
				o.put("name", tc.name());
				o.put("arguments", tc.arguments() != null ? tc.arguments() : new LinkedHashMap<String, Object>());
				break;
			}
			default: o.put("type", c.type());
		}
		return o;
	}

	public static Object toJson(AgentMessage m) {
		Map<String, Object> o = new LinkedHashMap<>();
		switch (m.role()) {
			case "user": {
				AgentMessage.UserMessage u = (AgentMessage.UserMessage) m;
				o.put("role", "user");
				o.put("content", contentList(u.content()));
				o.put("timestamp", u.timestamp());
				break;
			}
			case "assistant": {
				AgentMessage.AssistantMessage a = (AgentMessage.AssistantMessage) m;
				o.put("role", "assistant");
				o.put("content", contentList(a.content()));
				o.put("api", a.api());
				o.put("provider", a.provider());
				o.put("model", a.model());
				o.put("usage", toJson(a.usage()));
				o.put("stopReason", a.stopReason().wire());
				if (a.errorMessage() != null) o.put("errorMessage", a.errorMessage());
				o.put("timestamp", a.timestamp());
				break;
			}
			case "toolResult": {
				AgentMessage.ToolResultMessage t = (AgentMessage.ToolResultMessage) m;
				o.put("role", "toolResult");
				o.put("toolCallId", t.toolCallId());
				o.put("toolName", t.toolName());
				o.put("content", contentList(t.content()));
				if (t.details() != null) o.put("details", t.details());
				if (t.usage() != null) o.put("usage", toJson(t.usage()));
				if (t.addedToolNames() != null && !t.addedToolNames().isEmpty()) o.put("addedToolNames", t.addedToolNames());
				o.put("isError", t.isError());
				o.put("timestamp", t.timestamp());
				break;
			}
			default: {
				AgentMessage.CustomMessage c = (AgentMessage.CustomMessage) m;
				o.put("role", c.type());
				if (c.data() != null) o.putAll(c.data());
				o.put("timestamp", c.timestamp());
				break;
			}
		}
		return o;
	}

	private static List<Object> contentList(List<Content> contents) {
		List<Object> out = new java.util.ArrayList<>();
		for (Content c : contents) out.add(toJson(c));
		return out;
	}
}
