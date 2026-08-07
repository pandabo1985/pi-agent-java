package com.duokanbook.pi.agent;

import java.util.List;

/** Lifecycle events emitted by the agent loop for UI and observer updates. */
public interface AgentEvent {

	String type();

	final class AgentStart extends ValueObject implements AgentEvent {
		@Override public String type() { return "agent_start"; }
		@Override protected String[] componentNames() { return new String[0]; }
		@Override protected Object[] componentValues() { return new Object[0]; }
	}
	final class AgentEnd extends ValueObject implements AgentEvent {
		private final List<AgentMessage> messages;
		public AgentEnd(List<AgentMessage> messages) { this.messages = messages; }
		public List<AgentMessage> messages() { return messages; }
		@Override public String type() { return "agent_end"; }
		@Override protected String[] componentNames() { return new String[] {"messages"}; }
		@Override protected Object[] componentValues() { return new Object[] {messages}; }
	}
	final class TurnStart extends ValueObject implements AgentEvent {
		@Override public String type() { return "turn_start"; }
		@Override protected String[] componentNames() { return new String[0]; }
		@Override protected Object[] componentValues() { return new Object[0]; }
	}
	final class TurnEnd extends ValueObject implements AgentEvent {
		private final AgentMessage message;
		private final List<AgentMessage.ToolResultMessage> toolResults;
		public TurnEnd(AgentMessage message, List<AgentMessage.ToolResultMessage> toolResults) {
			this.message = message;
			this.toolResults = toolResults;
		}
		public AgentMessage message() { return message; }
		public List<AgentMessage.ToolResultMessage> toolResults() { return toolResults; }
		@Override public String type() { return "turn_end"; }
		@Override protected String[] componentNames() { return new String[] {"message", "toolResults"}; }
		@Override protected Object[] componentValues() { return new Object[] {message, toolResults}; }
	}
	final class MessageStart extends ValueObject implements AgentEvent {
		private final AgentMessage message;
		public MessageStart(AgentMessage message) { this.message = message; }
		public AgentMessage message() { return message; }
		@Override public String type() { return "message_start"; }
		@Override protected String[] componentNames() { return new String[] {"message"}; }
		@Override protected Object[] componentValues() { return new Object[] {message}; }
	}
	final class MessageUpdate extends ValueObject implements AgentEvent {
		private final AgentMessage message;
		private final AssistantMessageEvent assistantMessageEvent;
		public MessageUpdate(AgentMessage message, AssistantMessageEvent assistantMessageEvent) {
			this.message = message;
			this.assistantMessageEvent = assistantMessageEvent;
		}
		public AgentMessage message() { return message; }
		public AssistantMessageEvent assistantMessageEvent() { return assistantMessageEvent; }
		@Override public String type() { return "message_update"; }
		@Override protected String[] componentNames() { return new String[] {"message", "assistantMessageEvent"}; }
		@Override protected Object[] componentValues() { return new Object[] {message, assistantMessageEvent}; }
	}
	final class MessageEnd extends ValueObject implements AgentEvent {
		private final AgentMessage message;
		public MessageEnd(AgentMessage message) { this.message = message; }
		public AgentMessage message() { return message; }
		@Override public String type() { return "message_end"; }
		@Override protected String[] componentNames() { return new String[] {"message"}; }
		@Override protected Object[] componentValues() { return new Object[] {message}; }
	}
	final class ToolExecutionStart extends ValueObject implements AgentEvent {
		private final String toolCallId;
		private final String toolName;
		private final Object args;
		public ToolExecutionStart(String toolCallId, String toolName, Object args) {
			this.toolCallId = toolCallId;
			this.toolName = toolName;
			this.args = args;
		}
		public String toolCallId() { return toolCallId; }
		public String toolName() { return toolName; }
		public Object args() { return args; }
		@Override public String type() { return "tool_execution_start"; }
		@Override protected String[] componentNames() { return new String[] {"toolCallId", "toolName", "args"}; }
		@Override protected Object[] componentValues() { return new Object[] {toolCallId, toolName, args}; }
	}
	final class ToolExecutionUpdate extends ValueObject implements AgentEvent {
		private final String toolCallId;
		private final String toolName;
		private final Object args;
		private final Object partialResult;
		public ToolExecutionUpdate(String toolCallId, String toolName, Object args, Object partialResult) {
			this.toolCallId = toolCallId;
			this.toolName = toolName;
			this.args = args;
			this.partialResult = partialResult;
		}
		public String toolCallId() { return toolCallId; }
		public String toolName() { return toolName; }
		public Object args() { return args; }
		public Object partialResult() { return partialResult; }
		@Override public String type() { return "tool_execution_update"; }
		@Override protected String[] componentNames() { return new String[] {"toolCallId", "toolName", "args", "partialResult"}; }
		@Override protected Object[] componentValues() { return new Object[] {toolCallId, toolName, args, partialResult}; }
	}
	final class ToolExecutionEnd extends ValueObject implements AgentEvent {
		private final String toolCallId;
		private final String toolName;
		private final AgentToolResult<?> result;
		private final boolean isError;
		public ToolExecutionEnd(String toolCallId, String toolName, AgentToolResult<?> result, boolean isError) {
			this.toolCallId = toolCallId;
			this.toolName = toolName;
			this.result = result;
			this.isError = isError;
		}
		public String toolCallId() { return toolCallId; }
		public String toolName() { return toolName; }
		public AgentToolResult<?> result() { return result; }
		public boolean isError() { return isError; }
		@Override public String type() { return "tool_execution_end"; }
		@Override protected String[] componentNames() { return new String[] {"toolCallId", "toolName", "result", "isError"}; }
		@Override protected Object[] componentValues() { return new Object[] {toolCallId, toolName, result, isError}; }
	}
}
