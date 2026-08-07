package com.duokanbook.pi.agent;

/** Token usage reported by a provider for a single assistant message. Mirrors {@code pi-ai} {@code Usage}. */
public record Usage(
		long input,
		long output,
		long cacheRead,
		long cacheWrite,
		long totalTokens,
		Cost cost) {

	public record Cost(double input, double output, double cacheRead, double cacheWrite, double total) {}

	/** A zero-usage sentinel used for synthesized error/abort assistant messages. */
	public static Usage empty() {
		return new Usage(0, 0, 0, 0, 0, new Cost(0, 0, 0, 0, 0));
	}

	/**
	 * Effective context-token count, equivalent to {@code calculateContextTokens} in the
	 * TypeScript compaction module: {@code totalTokens} when reported, else the sum of buckets.
	 */
	public long contextTokens() {
		return totalTokens != 0 ? totalTokens : input + output + cacheRead + cacheWrite;
	}
}
