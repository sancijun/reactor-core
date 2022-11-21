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

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.util.context.Context;

/**
 * 与响应流中的原始 {@link org.reactivestreams.Subscriber} 相比，{@link Context} 感知订阅者放宽了 §1.3 和 §3.9 的规则。
 * 如果在接收到的订阅上执行了无效请求 {@code <= 0}，该请求将不会产生 onError 并且会被忽略。
 *
 * <p>
 * 规则放宽最初是在 reactive-streams-commons 下建立的。
 *
 * 为了保证Rx标准的统一，CoreSubscriber继承了org.reactivestreams.Subscriber接口，
 * 同时加入了一些Reactor 3特有的Context功能实现。
 * 如果订阅者发出的元素请求数量小于或等于0，则请求不会产生onError事件，而只会简单地忽略错误。
 *
 * @param <T> the {@link Subscriber} data type
 *
 * @since 3.1.0
 */
public interface CoreSubscriber<T> extends Subscriber<T> {

	/**
	 * 从依赖组件请求 {@link Context}，
	 * 其中可以包括订阅期间的下游运算符或终端 {@link org.reactivestreams.Subscriber}。
	 * 从currentContext的定义可知，其主要用于元素下发过程中的中间操作或者中间定义的订阅者上。
	 * 内部涉及的Context，主要用于存储此订阅者产生订阅到结束这一过程中的信息（比如异常信息、临时中间变量），
	 * 这些信息可以被订阅者获取，其有点类似于ThreadLocal，但它是针对多线程调度下Reactor特有的东西
	 *
	 * @return a resolved context or {@link Context#empty()}
	 */
	default Context currentContext(){
		return Context.empty();
	}

	/**
	 * Implementors should initialize any state used by {@link #onNext(Object)} before
	 * calling {@link Subscription#request(long)}. Should further {@code onNext} related
	 * state modification occur, thread-safety will be required.
	 * <p>
	 *    Note that an invalid request {@code <= 0} will not produce an onError and
	 *    will simply be ignored or reported through a debug-enabled
	 *    {@link reactor.util.Logger}.
	 *
	 * {@inheritDoc}
	 */
	@Override
	void onSubscribe(Subscription s);
}
