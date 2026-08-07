package com.duokanbook.pi.agent;

/** Token usage reported by a provider for a single assistant message. */
public final class Usage extends ValueObject {
	private final long input;
	private final long output;
	private final long cacheRead;
	private final long cacheWrite;
	private final long totalTokens;
	private final Cost cost;
	public Usage(long input, long output, long cacheRead, long cacheWrite, long totalTokens, Cost cost) {
		this.input = input;
		this.output = output;
		this.cacheRead = cacheRead;
		this.cacheWrite = cacheWrite;
		this.totalTokens = totalTokens;
		this.cost = cost;
	}
	public long input() { return input; }
	public long output() { return output; }
	public long cacheRead() { return cacheRead; }
	public long cacheWrite() { return cacheWrite; }
	public long totalTokens() { return totalTokens; }
	public Cost cost() { return cost; }
	public static Usage empty() { return new Usage(0, 0, 0, 0, 0, new Cost(0, 0, 0, 0, 0)); }
	public long contextTokens() { return totalTokens != 0 ? totalTokens : input + output + cacheRead + cacheWrite; }
	@Override protected String[] componentNames() {
		return new String[] {"input", "output", "cacheRead", "cacheWrite", "totalTokens", "cost"};
	}
	@Override protected Object[] componentValues() { return new Object[] {input, output, cacheRead, cacheWrite, totalTokens, cost}; }

	public static final class Cost extends ValueObject {
		private final double input;
		private final double output;
		private final double cacheRead;
		private final double cacheWrite;
		private final double total;
		public Cost(double input, double output, double cacheRead, double cacheWrite, double total) {
			this.input = input;
			this.output = output;
			this.cacheRead = cacheRead;
			this.cacheWrite = cacheWrite;
			this.total = total;
		}
		public double input() { return input; }
		public double output() { return output; }
		public double cacheRead() { return cacheRead; }
		public double cacheWrite() { return cacheWrite; }
		public double total() { return total; }
		@Override protected String[] componentNames() { return new String[] {"input", "output", "cacheRead", "cacheWrite", "total"}; }
		@Override protected Object[] componentValues() { return new Object[] {input, output, cacheRead, cacheWrite, total}; }
	}
}
