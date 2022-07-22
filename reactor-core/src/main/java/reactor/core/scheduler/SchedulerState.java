/*
 * Copyright (c) 2022 VMware Inc. or its affiliates, All Rights Reserved.
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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.annotation.Nullable;

final class SchedulerState<T> {

	final T resource;
	final Mono<Void>               onDispose;

	private SchedulerState(T resource, Mono<Void> onDispose) {
		this.resource = resource;
		this.onDispose = onDispose;
	}

	static <T> SchedulerState<T> fresh(final T resource, AwaitableResource<T> awaiter) {
		return new SchedulerState(
				resource,
				Flux.<Void>create(sink -> {
					    // TODO(dj): consider a shared pool for all disposeGracefully background tasks
					    // as part of Schedulers internal API
					    Thread backgroundThread = new Thread(() -> {
						    while (!Thread.currentThread()
						                  .isInterrupted()) {
							    try {
								    if (awaiter.await(resource, 1, TimeUnit.SECONDS)) {
									    sink.complete();
									    return;
								    }
							    }
							    catch (InterruptedException e) {
								    Thread.currentThread()
								          .interrupt();
								    return;
							    }
						    }
					    });
					    sink.onCancel(backgroundThread::interrupt);
					    backgroundThread.start();
				    })
				    .replay()
				    .refCount()
				    .next()
		);
	}

	static <T> SchedulerState<T> terminated(T tombstone, @Nullable SchedulerState<T> base) {
		return new SchedulerState<T>(tombstone, base == null ? Mono.empty() : base.onDispose);
	}

	interface AwaitableResource<T> {
		boolean await(T resource, long timeout, TimeUnit timeUnit) throws InterruptedException;
	}
}
