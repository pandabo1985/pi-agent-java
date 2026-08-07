package com.duokanbook.pi.agent;

/** Controls how queued messages are drained at a drain point. Mirrors {@code QueueMode} in {@code types.ts}. */
public enum QueueMode {
	/** Drain and inject every queued message at that point. */
	ALL,
	/** Drain and inject only the oldest queued message; leave the rest queued for later. */
	ONE_AT_A_TIME
}
