/*
 * Copyright (c) 2016-2022 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.scheduler;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

import reactor.core.Disposable;
import reactor.core.Scannable;
import reactor.core.publisher.Mono;


/**
 * Scheduler that works with a single-threaded ScheduledExecutorService and is suited for
 * same-thread work (like an event dispatch thread). This scheduler is time-capable (can
 * schedule with delay / periodically).
 */
final class SingleScheduler implements Scheduler,
									   Supplier<ScheduledExecutorService>,
									   SchedulerState.AwaitableResource<ScheduledExecutorService>,
                                       Scannable {



	static final ScheduledExecutorService TERMINATED;

	static {
		TERMINATED = Executors.newSingleThreadScheduledExecutor();
		TERMINATED.shutdownNow();
	}

	static final AtomicLong COUNTER       = new AtomicLong();

	final ThreadFactory factory;

	volatile SchedulerState<ScheduledExecutorService> state;
	private static final AtomicReferenceFieldUpdater<SingleScheduler, SchedulerState> STATE =
			AtomicReferenceFieldUpdater.newUpdater(
					SingleScheduler.class, SchedulerState.class, "state"
			);

	SingleScheduler(ThreadFactory factory) {
		this.factory = factory;
	}

	/**
	 * Instantiates the default {@link ScheduledExecutorService} for the SingleScheduler
	 * ({@code Executors.newScheduledThreadPoolExecutor} with core and max pool size of 1).
	 */
	@Override
	public ScheduledExecutorService get() {
		ScheduledThreadPoolExecutor e = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1, this.factory);
		e.setRemoveOnCancelPolicy(true);
		e.setMaximumPoolSize(1);
		return e;
	}

	@Override
	public boolean isDisposed() {
		// we only consider disposed as actually shutdown
		SchedulerState current = state;
		return current != null && current.resource == TERMINATED;
	}

	@Override
	public boolean await(ScheduledExecutorService resource, long timeout, TimeUnit timeUnit) throws InterruptedException {
		return resource.awaitTermination(timeout, timeUnit);
	}

	@Override
	public void start() {
		//TODO SingleTimedScheduler didn't implement start, check if any particular reason?
		SchedulerState<ScheduledExecutorService> b = null;
		for (; ; ) {
			SchedulerState<ScheduledExecutorService> a = state;
			if (a != null) {
				if (a.resource != TERMINATED) {
					if (b != null) {
						b.resource.shutdownNow();
					}
					return;
				}
			}

			if (b == null) {
				b = SchedulerState.fresh(
						Schedulers.decorateExecutorService(this, this.get()),
						this
				);
			}

			if (STATE.compareAndSet(this, a, b)) {
				return;
			}
		}
	}

	@Override
	public void dispose() {
		for (;;) {
			SchedulerState<ScheduledExecutorService> previous = state;

			if (previous != null && previous.resource == TERMINATED) {
				return;
			}

			if (STATE.compareAndSet(this, previous, SchedulerState.terminated(TERMINATED, previous))) {
				if (previous != null) {
					previous.resource.shutdownNow();
				}
				return;
			}
		}
	}

	@Override
	public Mono<Void> disposeGracefully(Duration gracePeriod) {
		return Mono.defer(() -> {
			for (;;) {
				SchedulerState<ScheduledExecutorService> previous = state;

				if (previous != null && previous.resource == TERMINATED) {
					return previous.onDispose;
				}

				SchedulerState<ScheduledExecutorService> next = SchedulerState.terminated(TERMINATED, previous);
				if (STATE.compareAndSet(this, previous, next)) {
					if (previous != null) {
						previous.resource.shutdown();
					}
					return next.onDispose;
				}
			}
		}).timeout(gracePeriod);
	}

	@Override
	public Disposable schedule(Runnable task) {
		ScheduledExecutorService executor = state.resource;
		return Schedulers.directSchedule(executor, task, null, 0L,
				TimeUnit.MILLISECONDS);
	}

	@Override
	public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
		return Schedulers.directSchedule(state.resource, task, null, delay, unit);
	}

	@Override
	public Disposable schedulePeriodically(Runnable task,
			long initialDelay,
			long period,
			TimeUnit unit) {
		return Schedulers.directSchedulePeriodically(state.resource,
				task,
				initialDelay,
				period,
				unit);
	}

	@Override
	public String toString() {
		StringBuilder ts = new StringBuilder(Schedulers.SINGLE)
				.append('(');
		if (factory instanceof ReactorThreadFactory) {
			ts.append('\"').append(((ReactorThreadFactory) factory).get()).append('\"');
		}
		return ts.append(')').toString();
	}

	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.TERMINATED || key == Attr.CANCELLED) return isDisposed();
		if (key == Attr.NAME) return this.toString();
		if (key == Attr.CAPACITY || key == Attr.BUFFERED) return 1; //BUFFERED: number of workers doesn't vary

		return Schedulers.scanExecutor(state.resource, key);
	}

	@Override
	public Worker createWorker() {
		return new ExecutorServiceWorker(state.resource);
	}
}
