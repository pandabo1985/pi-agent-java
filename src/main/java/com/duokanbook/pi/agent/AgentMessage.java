package com.duokanbook.pi.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Standard LLM messages plus an extensible custom message type. */
public interface AgentMessage {

	String role();
	long timestamp();

	final class UserMessage extends ValueObject implements AgentMessage {
		private final List<Content> content;
		private final long timestamp;

		public UserMessage(String text) {
			this(Collections.<Content>singletonList(new Content.Text(text)), System.currentTimeMillis());
		}
		public UserMessage(List<Content> content, long timestamp) {
			this.content = content;
			this.timestamp = timestamp;
		}
		public List<Content> content() { return content; }
		@Override public long timestamp() { return timestamp; }
		@Override public String role() { return "user"; }
		@Override protected String[] componentNames() { return new String[] {"content", "timestamp"}; }
		@Override protected Object[] componentValues() { return new Object[] {content, timestamp}; }
	}

	final class AssistantMessage extends ValueObject implements AgentMessage {
		private final List<Content> content;
		private final String api;
		private final String provider;
		private final String model;
		private final Usage usage;
		private final StopReason stopReason;
		private final String errorMessage;
		private final long timestamp;

		public AssistantMessage(List<Content> content, String api, String provider, String model, Usage usage,
				StopReason stopReason, String errorMessage, long timestamp) {
			this.content = content;
			this.api = api;
			this.provider = provider;
			this.model = model;
			this.usage = usage != null ? usage : Usage.empty();
			this.stopReason = stopReason;
			this.errorMessage = errorMessage;
			this.timestamp = timestamp;
		}
		public List<Content> content() { return content; }
		public String api() { return api; }
		public String provider() { return provider; }
		public String model() { return model; }
		public Usage usage() { return usage; }
		public StopReason stopReason() { return stopReason; }
		public String errorMessage() { return errorMessage; }
		@Override public long timestamp() { return timestamp; }
		@Override public String role() { return "assistant"; }
		@Override protected String[] componentNames() {
			return new String[] {"content", "api", "provider", "model", "usage", "stopReason", "errorMessage", "timestamp"};
		}
		@Override protected Object[] componentValues() {
			return new Object[] {content, api, provider, model, usage, stopReason, errorMessage, timestamp};
		}
	}

	final class ToolResultMessage extends ValueObject implements AgentMessage {
		private final String toolCallId;
		private final String toolName;
		private final List<Content> content;
		private final Object details;
		private final Usage usage;
		private final List<String> addedToolNames;
		private final boolean isError;
		private final long timestamp;

		public ToolResultMessage(String toolCallId, String toolName, List<Content> content, Object details,
				Usage usage, List<String> addedToolNames, boolean isError, long timestamp) {
			this.toolCallId = toolCallId;
			this.toolName = toolName;
			this.content = content;
			this.details = details;
			this.usage = usage;
			this.addedToolNames = addedToolNames;
			this.isError = isError;
			this.timestamp = timestamp;
		}
		public String toolCallId() { return toolCallId; }
		public String toolName() { return toolName; }
		public List<Content> content() { return content; }
		public Object details() { return details; }
		public Usage usage() { return usage; }
		public List<String> addedToolNames() { return addedToolNames; }
		public boolean isError() { return isError; }
		@Override public long timestamp() { return timestamp; }
		@Override public String role() { return "toolResult"; }
		@Override protected String[] componentNames() {
			return new String[] {"toolCallId", "toolName", "content", "details", "usage", "addedToolNames", "isError", "timestamp"};
		}
		@Override protected Object[] componentValues() {
			return new Object[] {toolCallId, toolName, content, details, usage, addedToolNames, isError, timestamp};
		}
	}

	final class CustomMessage extends ValueObject implements AgentMessage {
		private final String type;
		private final Map<String, Object> data;
		private final long timestamp;

		public CustomMessage(String type, Map<String, Object> data) { this(type, data, System.currentTimeMillis()); }
		public CustomMessage(String type, Map<String, Object> data, long timestamp) {
			this.type = type;
			this.data = data;
			this.timestamp = timestamp;
		}
		public String type() { return type; }
		public Map<String, Object> data() { return data; }
		@Override public long timestamp() { return timestamp; }
		@Override public String role() { return type; }
		@Override protected String[] componentNames() { return new String[] {"type", "data", "timestamp"}; }
		@Override protected Object[] componentValues() { return new Object[] {type, data, timestamp}; }
	}
}
