package com.duokanbook.pi.agent;

import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * An async, single-consumer stream of events that also carries a final result,
 * the Java analogue of {@code pi-ai}'s {@code EventStream<TEvent, TResult>}.
 *
 * <p>Producer side ({@code push}/{@code end}): runs on a virtual thread (e.g. an
 * HTTP reader). Pushes events in order, then completes with a terminal result via
 * {@link #end(Object)} (success) or {@link #completeExceptionally(Throwable)}.
 *
 * <p>Consumer side ({@code iterator()}): blocks on a {@link BlockingQueue}. The
 * agent loop iterates events with an enhanced-{@code for} and, on a {@code done}/
 * {@code error} event, reads the finalized result via {@link #result()}.
 *
 * <p>Thread-safety: safe for one producer and one consumer.
 */
public final class EventStream<E, R> implements Iterable<E> {

	/** Sentinel signaling end-of-stream, wrapped so any {@code E} value is representable. */
	private static final Optional<Object> END = Optional.empty();

	private final BlockingQueue<Optional<E>> queue = new LinkedBlockingQueue<>();
	private final CompletableFuture<R> result = new CompletableFuture<>();
	private volatile boolean closed = false;

	/** Push a non-terminal event. Ignored after the stream is closed. */
	public void push(E event) {
		if (closed) return;
		queue.add(Optional.of(event));
	}

	/** Complete the stream successfully and deliver the terminal result. */
	public void end(R value) {
		if (closed) return;
		closed = true;
		queue.add(typedEnd());
		result.complete(value);
	}

	/** Complete the stream exceptionally. */
	public void completeExceptionally(Throwable error) {
		if (closed) return;
		closed = true;
		queue.add(typedEnd());
		result.completeExceptionally(error);
	}

	/** Future completed with the terminal result when {@link #end} / {@code completeExceptionally} is called. */
	public CompletableFuture<R> result() {
		return result;
	}

	@SuppressWarnings("unchecked")
	private Optional<E> typedEnd() {
		return (Optional<E>) END;
	}

	@Override
	public Iterator<E> iterator() {
		return new BlockingIterator();
	}

	private final class BlockingIterator implements Iterator<E> {
		private Optional<E> next = takeNext();

		@Override
		public boolean hasNext() {
			return next.isPresent();
		}

		@Override
		public E next() {
			E current = next.get();
			next = takeNext();
			return current;
		}

		private Optional<E> takeNext() {
			try {
				return queue.take();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return typedEnd();
			}
		}
	}
}
