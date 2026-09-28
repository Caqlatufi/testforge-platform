/**
 * 基于 MySQL CAS 的失败分类、有限指数退避、重新排队和终态竞争收敛策略。
 *
 * <p>本包不实现心跳存储或 Reaper 扫描。租约模块只需把已经仲裁为 LOST 的 Attempt
 * 作为 {@code WORKER_LOST} 失败交给这里；持久化适配器通过 run-orchestrator 公开服务
 * 实现 {@link io.testforge.dispatcher.reliability.retry.RetryStateStore} 并登记延迟派发。</p>
 */
package io.testforge.dispatcher.reliability.retry;
