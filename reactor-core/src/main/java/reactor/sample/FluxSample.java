package reactor.sample;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * @author lixiaolong
 * @version FluxSample.java v1.0 2022-11-20 09:20
 */
public class FluxSample {

    public static void main(String []args){
        simple();
        publishOn();
    }

    public static void publishOn(){
        Flux.just("tom", "jack", "allen")
                .map(s -> {
                    System.out.println("(concat @qq.com) at [" + Thread.currentThread() + "]");
                    return s.concat("@qq.com");
                })
                // 上面线程的结果，如果给到下面线程
                .publishOn(Schedulers.newSingle("thread-a"))
                .map(s -> {
                    System.out.println("(concat foo) at [" + Thread.currentThread() + "]");
                    return s.concat("foo");
                })
                .subscribeOn(Schedulers.newSingle("source"))
                .subscribe(System.out::println);

    }

    private static void simple(){
        Flux.just("tom", "jack", "allen")
                .map(s-> s.concat("@qq.com"))
                .filter(s -> !s.startsWith("t"))
                .subscribe(System.out::println);
    }

}
