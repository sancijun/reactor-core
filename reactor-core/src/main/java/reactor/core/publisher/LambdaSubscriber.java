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

package reactor.core.publisher;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;


/**
 * {@link Subscriber} 的无界 Java Lambda 适配器
 * 在产生订阅时往往会自定义一些元素消费操作，这些操作会被Reactor 3包装成一个LambdaSubscriber类型的实例。
 * @param <T> the value type
 */
final class LambdaSubscriber<T>
		implements InnerConsumer<T>, Disposable {

	final Consumer<? super T>            consumer;
	final Consumer<? super Throwable>    errorConsumer;
	final Runnable                       completeConsumer;
	final Consumer<? super Subscription> subscriptionConsumer;
	// 将 subscription 封装为 原子类型
	volatile Subscription subscription;
	static final AtomicReferenceFieldUpdater<LambdaSubscriber, Subscription> S =
			AtomicReferenceFieldUpdater.newUpdater(LambdaSubscriber.class,
					Subscription.class,
					"subscription");

	/**
	 * 创建一个响应 onNext、onError 和 onComplete 的 {@link Subscriber}。
	 * 订阅者会自动请求 Long.MAX_VALUE onSubscribe。
	 * <p>
	 * 参数 {@code subscriptionHandler} 由新订阅者执行一次以生成每个请求调用共享的上下文。
	 *
	 * @param consumer A {@link Consumer} with argument onNext data
	 * @param errorConsumer A {@link Consumer} called onError
	 * @param completeConsumer A {@link Runnable} called onComplete with the actual
	 * context if any
	 * @param subscriptionConsumer A {@link Consumer} called with the {@link Subscription}
	 * to perform initial request, or null to request max
	 */
	LambdaSubscriber(
			@Nullable Consumer<? super T> consumer,
			@Nullable Consumer<? super Throwable> errorConsumer,
			@Nullable Runnable completeConsumer,
			@Nullable Consumer<? super Subscription> subscriptionConsumer) {
		this.consumer = consumer;
		this.errorConsumer = errorConsumer;
		this.completeConsumer = completeConsumer;
		this.subscriptionConsumer = subscriptionConsumer;
	}
	// 核心的都是调用了 Subscription.request() 函数

	/**
	 * 如果没有定义subscriptionConsumer，默认会最大化元素请求数量。
	 * 在消费下发元素的时候调用onNext方法
	 * @param s
	 */
	@Override
	public final void onSubscribe(Subscription s) {
		if (Operators.validate(subscription, s)) {
			this.subscription = s;
			if (subscriptionConsumer != null) {
				try {
					subscriptionConsumer.accept(s);
				}
				catch (Throwable t) {
					Exceptions.throwIfFatal(t);
					s.cancel();
					onError(t);
				}
			}
			else {
				s.request(Long.MAX_VALUE);
			}
		}
	}

	@Override
	public final void onComplete() {
		Subscription s = S.getAndSet(this, Operators.cancelledSubscription());
		if (s == Operators.cancelledSubscription()) {
			return;
		}
		if (completeConsumer != null) {
			try {
				completeConsumer.run();
			}
			catch (Throwable t) {
				Exceptions.throwIfFatal(t);
				onError(t);
			}
		}
	}

	@Override
	public final void onError(Throwable t) {
		Subscription s = S.getAndSet(this, Operators.cancelledSubscription());
		if (s == Operators.cancelledSubscription()) {
			Operators.onErrorDropped(t, Context.empty());
			return;
		}
		if (errorConsumer != null) {
			errorConsumer.accept(t);
		}
		else {
			throw Exceptions.errorCallbackNotImplemented(t);
		}
	}

	@Override
	public final void onNext(T x) {
		try {
			if (consumer != null) {
				consumer.accept(x);
			}
		}
		catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			this.subscription.cancel();
			onError(t);
		}
	}

	@Override
	@Nullable
	public Object scanUnsafe(Attr key) {
		if (key == Attr.PARENT) return subscription;
		if (key == Attr.PREFETCH) return Integer.MAX_VALUE;
		if (key == Attr.TERMINATED || key == Attr.CANCELLED) return isDisposed();

		return null;
	}


	@Override
	public boolean isDisposed() {
		return subscription == Operators.cancelledSubscription();
	}

	@Override
	public void dispose() {
		Subscription s = S.getAndSet(this, Operators.cancelledSubscription());
		if (s != null && s != Operators.cancelledSubscription()) {
			s.cancel();
		}
	}
}
