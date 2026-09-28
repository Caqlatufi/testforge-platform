package io.testforge.dispatcher.port.outbound;

/**
 * 将派发结果交给日志、指标或审计适配器；观测失败不能改变派发结论。
 */
@FunctionalInterface
public interface DispatchObservationPort {

    void observe(DispatchObservation observation);

    static DispatchObservationPort noop() {
        return observation -> {
        };
    }
}
