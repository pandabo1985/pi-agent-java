package com.duokanbook.pi.agent;

/**
 * Process-wide fallback stream function, the Java analogue of {@code stream-fn.ts}.
 *
 * <p>Hosts that provide a default model runtime install its stream function here without
 * forcing every {@link Agent} construction to pass one explicitly. Mirrors the TypeScript
 * {@code setDefaultStreamFn}/{@code getDefaultStreamFn} pair.
 */
public final class DefaultStreamFn {

	private static volatile StreamFn instance;

	private DefaultStreamFn() {}

	public static void set(StreamFn streamFn) {
		instance = streamFn;
	}

	public static StreamFn get() {
		StreamFn fn = instance;
		if (fn == null) {
			throw new IllegalStateException(
					"No default stream function configured. Pass streamFn explicitly or call DefaultStreamFn.set(...).");
		}
		return fn;
	}

	public static boolean isSet() {
		return instance != null;
	}
}
