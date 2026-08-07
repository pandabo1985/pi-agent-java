package com.duokanbook.pi.agent;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Per-request options forwarded to a {@link StreamFn}. */
public final class SimpleStreamOptions extends ValueObject {
	private final String apiKey;
	private final AbortSignal signal;
	private final Double temperature;
	private final Map<String, Object> samplingParams;
	private final Long maxTokens;
	private final ThinkingLevel reasoning;
	private final String cacheRetention;
	private final String sessionId;
	private final Map<String, String> headers;
	private final Map<String, Object> metadata;
	private final String transport;
	private final Object thinkingBudgets;
	private final Long maxRetryDelayMs;
	private final OnPayload onPayload;
	private final OnResponse onResponse;

	/** Retains the original direct constructor after optional fields were added. */
	public SimpleStreamOptions(
			String apiKey,
			AbortSignal signal,
			ThinkingLevel reasoning,
			String sessionId,
			String transport,
			Object thinkingBudgets,
			Long maxRetryDelayMs) {
		this(builder()
				.apiKey(apiKey)
				.signal(signal)
				.reasoning(reasoning)
				.sessionId(sessionId)
				.transport(transport)
				.thinkingBudgets(thinkingBudgets)
				.maxRetryDelayMs(maxRetryDelayMs));
	}

	private SimpleStreamOptions(Builder builder) {
		apiKey = builder.apiKey; signal = builder.signal; temperature = builder.temperature;
		samplingParams = builder.samplingParams; maxTokens = builder.maxTokens; reasoning = builder.reasoning;
		cacheRetention = builder.cacheRetention; sessionId = builder.sessionId; headers = builder.headers;
		metadata = builder.metadata; transport = builder.transport; thinkingBudgets = builder.thinkingBudgets;
		maxRetryDelayMs = builder.maxRetryDelayMs; onPayload = builder.onPayload; onResponse = builder.onResponse;
	}
	public String apiKey() { return apiKey; }
	public AbortSignal signal() { return signal; }
	public Double temperature() { return temperature; }
	public Map<String, Object> samplingParams() { return samplingParams; }
	public Long maxTokens() { return maxTokens; }
	public ThinkingLevel reasoning() { return reasoning; }
	public String cacheRetention() { return cacheRetention; }
	public String sessionId() { return sessionId; }
	public Map<String, String> headers() { return headers; }
	public Map<String, Object> metadata() { return metadata; }
	public String transport() { return transport; }
	public Object thinkingBudgets() { return thinkingBudgets; }
	public Long maxRetryDelayMs() { return maxRetryDelayMs; }
	public OnPayload onPayload() { return onPayload; }
	public OnResponse onResponse() { return onResponse; }
	@Override protected String[] componentNames() {
		return new String[] {"apiKey", "signal", "temperature", "samplingParams", "maxTokens", "reasoning",
				"cacheRetention", "sessionId", "headers", "metadata", "transport", "thinkingBudgets",
				"maxRetryDelayMs", "onPayload", "onResponse"};
	}
	@Override protected Object[] componentValues() {
		return new Object[] {apiKey, signal, temperature, samplingParams, maxTokens, reasoning, cacheRetention,
				sessionId, headers, metadata, transport, thinkingBudgets, maxRetryDelayMs, onPayload, onResponse};
	}
	public static Builder builder() { return new Builder(); }

	@FunctionalInterface public interface OnPayload { CompletableFuture<Object> apply(Object payload, Model model); }
	@FunctionalInterface public interface OnResponse { CompletableFuture<Void> apply(ProviderResponse response, Model model); }
	public static final class ProviderResponse extends ValueObject {
		private final int status;
		private final Map<String, String> headers;
		public ProviderResponse(int status, Map<String, String> headers) { this.status = status; this.headers = headers; }
		public int status() { return status; }
		public Map<String, String> headers() { return headers; }
		@Override protected String[] componentNames() { return new String[] {"status", "headers"}; }
		@Override protected Object[] componentValues() { return new Object[] {status, headers}; }
	}

	public static final class Builder {
		private String apiKey;
		private AbortSignal signal;
		private Double temperature;
		private Map<String, Object> samplingParams;
		private Long maxTokens;
		private ThinkingLevel reasoning;
		private String cacheRetention;
		private String sessionId;
		private Map<String, String> headers;
		private Map<String, Object> metadata;
		private String transport = "auto";
		private Object thinkingBudgets;
		private Long maxRetryDelayMs;
		private OnPayload onPayload;
		private OnResponse onResponse;
		public Builder apiKey(String value) { apiKey = value; return this; }
		public Builder signal(AbortSignal value) { signal = value; return this; }
		public Builder temperature(Double value) { temperature = value; return this; }
		public Builder samplingParams(Map<String, Object> value) { samplingParams = value; return this; }
		public Builder maxTokens(Long value) { maxTokens = value; return this; }
		public Builder reasoning(ThinkingLevel value) { reasoning = value; return this; }
		public Builder cacheRetention(String value) { cacheRetention = value; return this; }
		public Builder sessionId(String value) { sessionId = value; return this; }
		public Builder headers(Map<String, String> value) { headers = value; return this; }
		public Builder metadata(Map<String, Object> value) { metadata = value; return this; }
		public Builder transport(String value) { transport = value; return this; }
		public Builder thinkingBudgets(Object value) { thinkingBudgets = value; return this; }
		public Builder maxRetryDelayMs(Long value) { maxRetryDelayMs = value; return this; }
		public Builder onPayload(OnPayload value) { onPayload = value; return this; }
		public Builder onResponse(OnResponse value) { onResponse = value; return this; }
		public SimpleStreamOptions build() { return new SimpleStreamOptions(this); }
	}
}
