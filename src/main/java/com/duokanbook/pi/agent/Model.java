package com.duokanbook.pi.agent;

/**
 * A model descriptor. The agent core only reads {@link #api()}/{@link #provider()}/{@link #id()}
 * (to tag assistant messages and resolve API keys) and {@link #contextWindow()} (for compaction).
 * All other fields are carried through for the stream function.
 */
public record Model(
		String id,
		String name,
		String api,
		String provider,
		String baseUrl,
		boolean reasoning,
		int contextWindow,
		int maxTokens,
		Usage.Cost cost) {

	public static Model unknown() {
		return new Model("unknown", "unknown", "unknown", "unknown", "", false, 0, 0,
				new Usage.Cost(0, 0, 0, 0, 0));
	}
}
