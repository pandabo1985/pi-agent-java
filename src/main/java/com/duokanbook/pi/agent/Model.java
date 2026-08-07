package com.duokanbook.pi.agent;

/** A model descriptor carried through to stream functions. */
public final class Model extends ValueObject {
	private final String id;
	private final String name;
	private final String api;
	private final String provider;
	private final String baseUrl;
	private final boolean reasoning;
	private final int contextWindow;
	private final int maxTokens;
	private final Usage.Cost cost;
	public Model(String id, String name, String api, String provider, String baseUrl, boolean reasoning,
			int contextWindow, int maxTokens, Usage.Cost cost) {
		this.id = id;
		this.name = name;
		this.api = api;
		this.provider = provider;
		this.baseUrl = baseUrl;
		this.reasoning = reasoning;
		this.contextWindow = contextWindow;
		this.maxTokens = maxTokens;
		this.cost = cost;
	}
	public String id() { return id; }
	public String name() { return name; }
	public String api() { return api; }
	public String provider() { return provider; }
	public String baseUrl() { return baseUrl; }
	public boolean reasoning() { return reasoning; }
	public int contextWindow() { return contextWindow; }
	public int maxTokens() { return maxTokens; }
	public Usage.Cost cost() { return cost; }
	@Override protected String[] componentNames() {
		return new String[] {"id", "name", "api", "provider", "baseUrl", "reasoning", "contextWindow", "maxTokens", "cost"};
	}
	@Override protected Object[] componentValues() {
		return new Object[] {id, name, api, provider, baseUrl, reasoning, contextWindow, maxTokens, cost};
	}
	public static Model unknown() {
		return new Model("unknown", "unknown", "unknown", "unknown", "", false, 0, 0,
				new Usage.Cost(0, 0, 0, 0, 0));
	}
}
