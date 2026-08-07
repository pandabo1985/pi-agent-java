package com.duokanbook.pi.agent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cooperative cancellation signal, the Java analogue of the web {@code AbortSignal}
 * / TypeScript {@code AbortSignal} threaded through the agent loop.
 *
 * <p>The agent loop creates one {@code AbortSignal} per run and passes it to the
 * stream function and to every tool {@code execute} call. Producers (HTTP clients,
 * long-running tools) register listeners via {@link #addListener(Runnable)} to be
 * notified when {@link #abort()} is invoked, and poll {@link #isAborted()} at
 * cancellation points.
 *
 * <p>This is intentionally a small, dependency-free cooperative flag — it does not
 * forcibly interrupt threads. Blocking operations that wish to be cancellable should
 * register a listener that cancels the underlying operation (e.g. closing an HTTP
 * response, cancelling a {@code Future}).
 */
public final class AbortSignal {

	private volatile boolean aborted = false;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	/** Returns {@code true} once {@link #abort()} has been called. */
	public boolean isAborted() {
		return aborted;
	}

	/** Abort the run. Notifies all registered listeners once. Idempotent. */
	public void abort() {
		if (aborted) return;
		aborted = true;
		for (Runnable l : listeners) {
			try {
				l.run();
			} catch (RuntimeException ignored) {
				// A failing listener must not prevent other listeners from running.
			}
		}
	}

	/**
	 * Register a listener fired when {@link #abort()} is called. If already aborted,
	 * the listener runs immediately. Returns itself for chaining/unregister use.
	 */
	public Runnable addListener(Runnable listener) {
		if (aborted) {
			listener.run();
		} else {
			listeners.add(listener);
		}
		return listener;
	}

	public void removeListener(Runnable listener) {
		listeners.remove(listener);
	}

	/** Throw {@link AbortedException} if this signal has been aborted. */
	public void check() {
		if (aborted) throw new AbortedException("Operation aborted");
	}

	/** Raised by {@link #check()} and by abort-aware operations. */
	public static final class AbortedException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public AbortedException(String message) {
			super(message);
		}
	}
}
