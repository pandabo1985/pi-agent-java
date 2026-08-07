package com.duokanbook.pi.agent;

/** Streaming protocol events produced by a {@link StreamFn}. */
public interface AssistantMessageEvent {

	String type();
	AgentMessage.AssistantMessage partialMessage();

	abstract class PartialEvent extends ValueObject implements AssistantMessageEvent {
		private final AgentMessage.AssistantMessage partial;
		PartialEvent(AgentMessage.AssistantMessage partial) { this.partial = partial; }
		@Override public AgentMessage.AssistantMessage partialMessage() { return partial; }
	}
	final class Start extends PartialEvent {
		public Start(AgentMessage.AssistantMessage partial) { super(partial); }
		@Override public String type() { return "start"; }
		@Override protected String[] componentNames() { return new String[] {"partial"}; }
		@Override protected Object[] componentValues() { return new Object[] {partialMessage()}; }
	}
	abstract class IndexedEvent extends PartialEvent {
		private final int contentIndex;
		IndexedEvent(int contentIndex, AgentMessage.AssistantMessage partial) { super(partial); this.contentIndex = contentIndex; }
		public int contentIndex() { return contentIndex; }
	}
	final class TextStart extends IndexedEvent {
		public TextStart(int contentIndex, AgentMessage.AssistantMessage partial) { super(contentIndex, partial); }
		@Override public String type() { return "text_start"; }
		@Override protected String[] componentNames() { return new String[] {"contentIndex", "partial"}; }
		@Override protected Object[] componentValues() { return new Object[] {contentIndex(), partialMessage()}; }
	}
	final class TextDelta extends IndexedEvent {
		private final String delta;
		public TextDelta(int contentIndex, String delta, AgentMessage.AssistantMessage partial) { super(contentIndex, partial); this.delta = delta; }
		public String delta() { return delta; }
		@Override public String type() { return "text_delta"; }
		@Override protected String[] componentNames() { return new String[] {"contentIndex", "delta", "partial"}; }
		@Override protected Object[] componentValues() { return new Object[] {contentIndex(), delta, partialMessage()}; }
	}
	final class TextEnd extends IndexedEvent {
		private final String content;
		public TextEnd(int contentIndex, String content, AgentMessage.AssistantMessage partial) { super(contentIndex, partial); this.content = content; }
		public String content() { return content; }
		@Override public String type() { return "text_end"; }
		@Override protected String[] componentNames() { return new String[] {"contentIndex", "content", "partial"}; }
		@Override protected Object[] componentValues() { return new Object[] {contentIndex(), content, partialMessage()}; }
	}
	final class ThinkingStart extends IndexedEvent {
		public ThinkingStart(int contentIndex, AgentMessage.AssistantMessage partial) { super(contentIndex, partial); }
		@Override public String type() { return "thinking_start"; }
		@Override protected String[] componentNames() { return new String[] {"contentIndex", "partial"}; }
		@Override protected Object[] componentValues() { return new Object[] {contentIndex(), partialMessage()}; }
	}
	final class ThinkingDelta extends IndexedEvent {
		private final String delta;
		public ThinkingDelta(int contentIndex, String delta, AgentMessage.AssistantMessage partial) { super(contentIndex, partial); this.delta = delta; }
		public String delta() { return delta; }
		@Override public String type() { return "thinking_delta"; }
		@Override protected String[] componentNames() { return new String[] {"contentIndex", "delta", "partial"}; }
		@Override protected Object[] componentValues() { return new Object[] {contentIndex(), delta, partialMessage()}; }
	}
	final class ThinkingEnd extends IndexedEvent {
		private final String content;
		public ThinkingEnd(int contentIndex, String content, AgentMessage.AssistantMessage partial) { super(contentIndex, partial); this.content = content; }
		public String content() { return content; }
		@Override public String type() { return "thinking_end"; }
		@Override protected String[] componentNames() { return new String[] {"contentIndex", "content", "partial"}; }
		@Override protected Object[] componentValues() { return new Object[] {contentIndex(), content, partialMessage()}; }
	}
	final class ToolCallStart extends IndexedEvent {
		public ToolCallStart(int contentIndex, AgentMessage.AssistantMessage partial) { super(contentIndex, partial); }
		@Override public String type() { return "toolcall_start"; }
		@Override protected String[] componentNames() { return new String[] {"contentIndex", "partial"}; }
		@Override protected Object[] componentValues() { return new Object[] {contentIndex(), partialMessage()}; }
	}
	final class ToolCallDelta extends IndexedEvent {
		private final String delta;
		public ToolCallDelta(int contentIndex, String delta, AgentMessage.AssistantMessage partial) { super(contentIndex, partial); this.delta = delta; }
		public String delta() { return delta; }
		@Override public String type() { return "toolcall_delta"; }
		@Override protected String[] componentNames() { return new String[] {"contentIndex", "delta", "partial"}; }
		@Override protected Object[] componentValues() { return new Object[] {contentIndex(), delta, partialMessage()}; }
	}
	final class ToolCallEnd extends IndexedEvent {
		public ToolCallEnd(int contentIndex, AgentMessage.AssistantMessage partial) { super(contentIndex, partial); }
		@Override public String type() { return "toolcall_end"; }
		@Override protected String[] componentNames() { return new String[] {"contentIndex", "partial"}; }
		@Override protected Object[] componentValues() { return new Object[] {contentIndex(), partialMessage()}; }
	}
	final class Done extends PartialEvent {
		private final StopReason reason;
		public Done(StopReason reason, AgentMessage.AssistantMessage message) { super(message); this.reason = reason; }
		public StopReason reason() { return reason; }
		public AgentMessage.AssistantMessage message() { return partialMessage(); }
		@Override public String type() { return "done"; }
		@Override protected String[] componentNames() { return new String[] {"reason", "message"}; }
		@Override protected Object[] componentValues() { return new Object[] {reason, message()}; }
	}
	final class ErrorEvent extends PartialEvent {
		private final StopReason reason;
		private final String errorMessage;
		public ErrorEvent(StopReason reason, String errorMessage, AgentMessage.AssistantMessage message) {
			super(message);
			this.reason = reason;
			this.errorMessage = errorMessage;
		}
		public StopReason reason() { return reason; }
		public String errorMessage() { return errorMessage; }
		public AgentMessage.AssistantMessage message() { return partialMessage(); }
		@Override public String type() { return "error"; }
		@Override protected String[] componentNames() { return new String[] {"reason", "errorMessage", "message"}; }
		@Override protected Object[] componentValues() { return new Object[] {reason, errorMessage, message()}; }
	}
}
