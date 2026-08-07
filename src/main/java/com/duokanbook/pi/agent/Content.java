package com.duokanbook.pi.agent;

/** A content block inside an assistant, user, or tool-result message. */
public interface Content {

	String type();

	final class Text extends ValueObject implements Content {
		private final String text;
		private final String textSignature;

		public Text(String text) { this(text, null); }
		public Text(String text, String textSignature) {
			this.text = text;
			this.textSignature = textSignature;
		}
		public String text() { return text; }
		public String textSignature() { return textSignature; }
		@Override public String type() { return "text"; }
		@Override protected String[] componentNames() { return new String[] {"text", "textSignature"}; }
		@Override protected Object[] componentValues() { return new Object[] {text, textSignature}; }
	}

	final class Image extends ValueObject implements Content {
		private final String data;
		private final String mimeType;

		public Image(String data, String mimeType) {
			this.data = data;
			this.mimeType = mimeType;
		}
		public String data() { return data; }
		public String mimeType() { return mimeType; }
		@Override public String type() { return "image"; }
		@Override protected String[] componentNames() { return new String[] {"data", "mimeType"}; }
		@Override protected Object[] componentValues() { return new Object[] {data, mimeType}; }
	}

	final class Thinking extends ValueObject implements Content {
		private final String thinking;
		private final String thinkingSignature;

		public Thinking(String thinking) { this(thinking, null); }
		public Thinking(String thinking, String thinkingSignature) {
			this.thinking = thinking;
			this.thinkingSignature = thinkingSignature;
		}
		public String thinking() { return thinking; }
		public String thinkingSignature() { return thinkingSignature; }
		@Override public String type() { return "thinking"; }
		@Override protected String[] componentNames() { return new String[] {"thinking", "thinkingSignature"}; }
		@Override protected Object[] componentValues() { return new Object[] {thinking, thinkingSignature}; }
	}

	final class ToolCall extends ValueObject implements Content {
		private final String id;
		private final String name;
		private final Object arguments;

		public ToolCall(String id, String name, Object arguments) {
			this.id = id;
			this.name = name;
			this.arguments = arguments;
		}
		public String id() { return id; }
		public String name() { return name; }
		public Object arguments() { return arguments; }
		@Override public String type() { return "toolCall"; }
		@Override protected String[] componentNames() { return new String[] {"id", "name", "arguments"}; }
		@Override protected Object[] componentValues() { return new Object[] {id, name, arguments}; }
	}
}
