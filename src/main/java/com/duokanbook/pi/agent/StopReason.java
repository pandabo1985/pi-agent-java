package com.duokanbook.pi.agent;

/** Reason an assistant message stopped generating. Mirrors {@code pi-ai} {@code StopReason}. */
public enum StopReason {
	PENDING("pending"),
	STOP("stop"),
	LENGTH("length"),
	TOOL_USE("toolUse"),
	ABORTED("aborted"),
	ERROR("error");

	private final String wire;

	StopReason(String wire) {
		this.wire = wire;
	}

	/** The lowercase wire string used in serialized payloads. */
	public String wire() {
		return wire;
	}

	public static StopReason fromWire(String s) {
		for (StopReason r : values()) {
			if (r.wire.equals(s)) return r;
		}
		throw new IllegalArgumentException("Unknown stopReason: " + s);
	}
}
