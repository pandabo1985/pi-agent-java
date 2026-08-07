package com.duokanbook.pi.agent;

/** Reasoning/thinking level requested for a turn. {@code OFF} maps to no reasoning in the LLM request. */
public enum ThinkingLevel {
	OFF,
	MINIMAL,
	LOW,
	MEDIUM,
	HIGH,
	XHIGH,
	MAX
}
