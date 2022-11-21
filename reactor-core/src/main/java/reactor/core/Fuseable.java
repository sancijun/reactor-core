/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.Callable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.util.annotation.Nullable;

/**
 * 用于流融合的微型 API，特别是标记支持 {@link QueueSubscription} 的生产者。
 * 用于对那些内部有队列支持的subscription进行优化。
 */
public interface Fuseable {

	/** 表示 QueueSubscription 不支持请求的模式。 */
	int NONE = 0;
	/** 指示 QueueSubscription 可以执行同步融合。 */
	int SYNC = 1;
	/** 指示 QueueSubscription 只能执行异步融合。 */
	int ASYNC = 2;
	/** 指示 QueueSubscription 应该决定它执行什么融合（仅限输入）。 */
	int ANY = 3;
	/**
	 * 指示队列将从另一个线程中排出，因此此时任何队列退出计算都可能无效。
	 * <p>
	 * 例如，一个 {@code asyncSource.map().publishOn().subscribe()} 序列，
	 * 其中 {@code asyncSource} 是异步可融合的：publishOn 可以将整个序列融合到一个队列中。
	 * 这反过来可以从另一个线程的 {@code poll()} 方法调用映射器函数，而未融合的序列会在前一个线程上调用映射器。
	 * 如果这样的映射器调用代价高昂，它将以这种方式逃避其线程边界。
	 */
	int THREAD_BARRIER = 4;

	/**
	 * 订阅者变体可以立即判断它是否消耗了该值，如果没有消耗则直接允许发送新值。
	 * 这避免了通常的 request(1) 丢弃值的往返。
	 *
	 * @param <T> the value type
	 */
	interface ConditionalSubscriber<T> extends CoreSubscriber<T> {
		/**
		 * 尝试使用该值并在成功时返回 true。
		 * @param t the value to consume, not null
		 * @return true if consumed, false if dropped and a new value can be immediately sent
		 */
		boolean tryOnNext(T t);
	}

	/**
	 * 支持基于队列融合的订阅优化合同。
	 *
	 * <ul>
	 *  <li>
	 *  具有固定大小并且可以以拉取方式发出其项目的同步源，因此在许多情况下避免了请求会计开销。
	 *  </li>
	 *  <li>
	 *  可以同时充当队列和订阅的异步源，节省了大部分时间分配另一个队列的时间。
	 * </li>
	 * </ul>
	 *
	 * <p>
	 *
	 * @param <T> the value type emitted
	 */
	interface QueueSubscription<T> extends Queue<T>, Subscription {
		
		String NOT_SUPPORTED_MESSAGE = "Although QueueSubscription extends Queue it is purely internal" +
				" and only guarantees support for poll/clear/size/isEmpty." +
				" Instances shouldn't be used/exposed as Queue outside of Reactor operators.";

		/**
		 * 从此 QueueSubscription 请求特定的融合模式。
		 * <p>
		 * 应该请求 SYNC、ASYNC 或 ANY 模式（绝不是 NONE）并且实现者应该返回 NONE、SYNC 或 ASYNC（绝不是 ANY）。
		 * <p>
		 * 例如，如果源仅支持 ASYNC 融合，但中间操作员仅支持 SYNC 可融合源，则操作员可能会请求 SYNC 融合，并且源可以通过 NONE 拒绝它，因此操作员也可以向下游返回 NONE，并且融合不会不会发生。
		 *
		 * @param requestedMode the mode requested by the intermediate operator
		 * @return the actual fusion mode activated
		 */
		int requestFusion(int requestedMode);

		
		@Override
		@Nullable
		default T peek() {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean add(@Nullable T t) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean offer(@Nullable T t) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default T remove() {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default T element() {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean contains(@Nullable Object o) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default Iterator<T> iterator() {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default Object[] toArray() {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default <T1> T1[] toArray(T1[] a) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean remove(@Nullable Object o) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean containsAll(Collection<?> c) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean addAll(Collection<? extends T> c) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean removeAll(Collection<?> c) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}

		@Override
		default boolean retainAll(Collection<?> c) {
			throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
		}
	}

	/**
	 * Base class for synchronous sources which have fixed size and can
	 * emit their items in a pull fashion, thus avoiding the request-accounting
	 * overhead in many cases.
	 *
	 * @param <T> the content value type
	 */
	interface SynchronousSubscription<T> extends QueueSubscription<T> {

		@Override
		default int requestFusion(int requestedMode) {
			if ((requestedMode & Fuseable.SYNC) != 0) {
				return Fuseable.SYNC;
			}
			return NONE;
		}

	}

	/**
	 * Marker interface indicating that the target can return a value or null,
	 * otherwise fail immediately and thus a viable target for assembly-time
	 * optimizations.
	 *
	 * @param <T> the value type returned
	 */
	interface ScalarCallable<T> extends Callable<T> { }
}