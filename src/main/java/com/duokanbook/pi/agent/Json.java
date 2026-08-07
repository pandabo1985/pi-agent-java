package com.duokanbook.pi.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON utility used by the agent core.
 *
 * <p>The core intentionally avoids any external JSON library (mirroring the
 * TypeScript package's "no provider dependency" stance). This class provides:
 * <ul>
 *   <li>{@link #parse(String)} — strict parse into {@code Map/List/String/Double/Boolean/null}.</li>
 *   <li>{@link #stringify(Object)} — serialize those same values.</li>
 *   <li>{@link #parseStreamingJson(String)} — best-effort salvage of a partial JSON
 *       document, equivalent to {@code pi-ai}'s streaming JSON parser. Used to make
 *       incremental tool-call argument deltas inspectable before they finalize.</li>
 * </ul>
 *
 * <p>JSON objects become {@link LinkedHashMap} (insertion-ordered); arrays become
 * {@link ArrayList}; numbers become {@link Double}.
 */
public final class Json {

	private Json() {}

	// ───────────────────────── parsing ─────────────────────────

	public static Object parse(String src) {
		Parser p = new Parser(src);
		p.skipWs();
		Object v = p.parseValue();
		p.skipWs();
		if (p.pos < p.src.length()) {
			throw new IllegalArgumentException("Unexpected trailing JSON at " + p.pos);
		}
		return v;
	}

	private static final class Parser {
		final String src;
		int pos;

		Parser(String s) { this.src = s; }

		void skipWs() {
			while (pos < src.length()) {
				char c = src.charAt(pos);
				if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
				else break;
			}
		}

		Object parseValue() {
			skipWs();
			if (pos >= src.length()) throw new IllegalArgumentException("Unexpected end of JSON");
			char c = src.charAt(pos);
			return switch (c) {
				case '{' -> parseObject();
				case '[' -> parseArray();
				case '"' -> parseString();
				case 't', 'f' -> parseBool();
				case 'n' -> parseNull();
				default -> parseNumber();
			};
		}

		Map<String, Object> parseObject() {
			Map<String, Object> m = new LinkedHashMap<>();
			pos++; // {
			skipWs();
			if (peek() == '}') { pos++; return m; }
			while (true) {
				skipWs();
				if (peek() != '"') throw new IllegalArgumentException("Expected string key at " + pos);
				String k = parseString();
				skipWs();
				if (peek() != ':') throw new IllegalArgumentException("Expected ':' at " + pos);
				pos++;
				Object v = parseValue();
				m.put(k, v);
				skipWs();
				char c = peek();
				if (c == ',') { pos++; continue; }
				if (c == '}') { pos++; return m; }
				throw new IllegalArgumentException("Expected ',' or '}' at " + pos);
			}
		}

		List<Object> parseArray() {
			List<Object> a = new ArrayList<>();
			pos++; // [
			skipWs();
			if (peek() == ']') { pos++; return a; }
			while (true) {
				Object v = parseValue();
				a.add(v);
				skipWs();
				char c = peek();
				if (c == ',') { pos++; continue; }
				if (c == ']') { pos++; return a; }
				throw new IllegalArgumentException("Expected ',' or ']' at " + pos);
			}
		}

		String parseString() {
			StringBuilder sb = new StringBuilder();
			pos++; // opening quote
			while (pos < src.length()) {
				char c = src.charAt(pos++);
				if (c == '"') return sb.toString();
				if (c == '\\') {
					if (pos >= src.length()) throw new IllegalArgumentException("Unterminated escape");
					char e = src.charAt(pos++);
					switch (e) {
						case '"' -> sb.append('"');
						case '\\' -> sb.append('\\');
						case '/' -> sb.append('/');
						case 'b' -> sb.append('\b');
						case 'f' -> sb.append('\f');
						case 'n' -> sb.append('\n');
						case 'r' -> sb.append('\r');
						case 't' -> sb.append('\t');
						case 'u' -> {
							if (pos + 4 > src.length()) throw new IllegalArgumentException("Bad \\u escape");
							int cp = Integer.parseInt(src.substring(pos, pos + 4), 16);
							sb.append((char) cp);
							pos += 4;
						}
						default -> throw new IllegalArgumentException("Bad escape \\" + e);
					}
				} else {
					sb.append(c);
				}
			}
			throw new IllegalArgumentException("Unterminated string");
		}

		Object parseNumber() {
			int start = pos;
			while (pos < src.length()) {
				char c = src.charAt(pos);
				if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++;
				else break;
			}
			if (pos == start) throw new IllegalArgumentException("Invalid token at " + start);
			return Double.parseDouble(src.substring(start, pos));
		}

		Boolean parseBool() {
			if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
			if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
			throw new IllegalArgumentException("Invalid literal at " + pos);
		}

		Object parseNull() {
			if (src.startsWith("null", pos)) { pos += 4; return null; }
			throw new IllegalArgumentException("Invalid literal at " + pos);
		}

		char peek() {
			if (pos >= src.length()) throw new IllegalArgumentException("Unexpected end of JSON at " + pos);
			return src.charAt(pos);
		}
	}

	// ───────────────────────── stringifying ─────────────────────────

	public static String stringify(Object v) {
		StringBuilder sb = new StringBuilder();
		write(sb, v);
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private static void write(StringBuilder sb, Object v) {
		if (v == null) { sb.append("null"); return; }
		if (v instanceof String s) { writeString(sb, s); return; }
		if (v instanceof Boolean b) { sb.append(b.booleanValue()); return; }
		if (v instanceof Number n) {
			double d = n.doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append("null"); return; }
			if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
				sb.append((long) d);
			} else {
				sb.append(d);
			}
			return;
		}
		if (v instanceof Map<?, ?> m) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : m.entrySet()) {
				if (!first) sb.append(',');
				first = false;
				writeString(sb, String.valueOf(e.getKey()));
				sb.append(':');
				write(sb, e.getValue());
			}
			sb.append('}');
			return;
		}
		if (v instanceof Iterable<?> it) {
			sb.append('[');
			boolean first = true;
			for (Object o : it) {
				if (!first) sb.append(',');
				first = false;
				write(sb, o);
			}
			sb.append(']');
			return;
		}
		// Fallback: stringify arbitrary object as its toString in a string literal.
		writeString(sb, String.valueOf(v));
	}

	private static void writeString(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}

	// ───────────────────────── streaming salvage ─────────────────────────

	/**
	 * Best-effort parse of a possibly-truncated JSON document, mirroring
	 * {@code pi-ai}'s {@code parseStreamingJson}. Closes open arrays/objects,
	 * drops dangling commas / keys without values, and returns the salvageable
	 * value (an empty map if nothing coherent can be recovered).
	 *
	 * <p>Used while tool-call argument deltas are still streaming: we want to
	 * expose the partial arguments object to the UI without waiting for
	 * {@code toolcall_end}.
	 */
	public static Object parseStreamingJson(String src) {
		if (src == null || src.isBlank()) return new LinkedHashMap<String, Object>();
		try {
			return parse(src);
		} catch (RuntimeException ignored) {
			return salvage(src);
		}
	}

	private static Object salvage(String src) {
		// Trim trailing incomplete token: drop a dangling comma, or a key/string/value cut mid-way.
		String s = src.trim();
		// Strip a trailing incomplete string/number/key sequence by trimming back to last structural char.
		// Then balance brackets.
		int cut = s.length();
		// Walk back over characters that are not structural closers/openers we can balance.
		while (cut > 0) {
			char c = s.charAt(cut - 1);
			if (c == '{' || c == '[' || c == '}' || c == ']' || c == '"' || c == ':' || c == ',') break;
			cut--;
		}
		// If we stopped at a quote, the last value was a string being written; drop the whole dangling token.
		s = s.substring(0, cut);
		// Drop a trailing comma or colon (no following value).
		while (s.endsWith(",") || s.endsWith(":")) {
			s = s.substring(0, s.length() - 1).trim();
		}
		// Balance open brackets/braces.
		StringBuilder b = new StringBuilder(s);
		int braces = 0, brackets = 0;
		boolean inStr = false, esc = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (inStr) {
				if (esc) esc = false;
				else if (c == '\\') esc = true;
				else if (c == '"') inStr = false;
			} else {
				if (c == '"') inStr = true;
				else if (c == '{') braces++;
				else if (c == '}') braces--;
				else if (c == '[') brackets++;
				else if (c == ']') brackets--;
			}
		}
		// If a string is still open, close it (the dangling value case is already trimmed above,
		// but a key-only like {"a may reach here). Close quote and add null value.
		if (inStr) b.append('"');
		for (int i = 0; i < brackets; i++) b.append(']');
		for (int i = 0; i < braces; i++) b.append('}');
		try {
			Object v = parse(b.toString());
			return v != null ? v : new LinkedHashMap<String, Object>();
		} catch (RuntimeException e2) {
			return new LinkedHashMap<String, Object>();
		}
	}
}
