package com.duokanbook.pi.agent;

/**
 * Per-request options forwarded to a {@link StreamFn}. A trimmed equivalent of
 * {@code pi-ai}'s {@code SimpleStreamOptions} — only the fields the core reads/writes.
 *
 * @param apiKey           resolved API key (from {@code getApiKey} or the static config)
 * @param signal           abort signal for this run
 * @param reasoning        requested thinking level; {@code null} means "no reasoning"
 * @param sessionId        forwarded to providers for cache-aware backends
 * @param transport        preferred transport hint (e.g. {@code "auto"}); may be {@code null}
 * @param thinkingBudgets  opaque per-level token budgets, passed through unchanged
 * @param maxRetryDelayMs  optional cap on provider-requested retry delays
 */
public record SimpleStreamOptions(
		String apiKey,
		AbortSignal signal,
		ThinkingLevel reasoning,
		String sessionId,
		String transport,
		Object thinkingBudgets,
		Long maxRetryDelayMs) {

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private String apiKey;
		private AbortSignal signal;
		private ThinkingLevel reasoning;
		private String sessionId;
		private String transport = "auto";
		private Object thinkingBudgets;
		private Long maxRetryDelayMs;

		public Builder apiKey(String v) { this.apiKey = v; return this; }
		public Builder signal(AbortSignal v) { this.signal = v; return this; }
		public Builder reasoning(ThinkingLevel v) { this.reasoning = v; return this; }
		public Builder sessionId(String v) { this.sessionId = v; return this; }
		public Builder transport(String v) { this.transport = v; return this; }
		public Builder thinkingBudgets(Object v) { this.thinkingBudgets = v; return this; }
		public Builder maxRetryDelayMs(Long v) { this.maxRetryDelayMs = v; return this; }

		public SimpleStreamOptions build() {
			return new SimpleStreamOptions(apiKey, signal, reasoning, sessionId, transport, thinkingBudgets, maxRetryDelayMs);
		}
	}
}
