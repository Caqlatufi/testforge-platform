/**
 * Attempt 租约签发、心跳延期与失联回收。
 *
 * <p>MySQL 持久化实现通过 {@link io.testforge.dispatcher.reliability.lease.AttemptLeaseStore}
 * 接入；本包不包含重试次数、退避或重新派发策略。</p>
 */
package io.testforge.dispatcher.reliability.lease;
