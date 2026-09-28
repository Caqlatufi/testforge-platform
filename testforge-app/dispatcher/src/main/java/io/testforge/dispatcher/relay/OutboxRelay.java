package io.testforge.dispatcher.relay;

import io.testforge.dispatcher.outbox.model.OutboxClaim;
import io.testforge.dispatcher.outbox.service.OutboxClaimService;
import io.testforge.dispatcher.port.outbound.OutboxMessage;
import io.testforge.dispatcher.port.outbound.OutboxMessagePublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 每次调用领取一批事件并按至少一次语义投递。发布成功到标记成功之间崩溃会导致重复消息，
 * 但事件会在租约过期后恢复，不会永久丢失。
 */
public class OutboxRelay {

    private final OutboxClaimService claimService;
    private final Supplier<OutboxMessagePublisher> publisherSupplier;
    private final RelayRetryPolicy retryPolicy;
    private final Clock clock;

    public OutboxRelay(
            OutboxClaimService claimService,
            Supplier<OutboxMessagePublisher> publisherSupplier,
            RelayRetryPolicy retryPolicy,
            Clock clock
    ) {
        this.claimService = Objects.requireNonNull(claimService, "claimService must not be null");
        this.publisherSupplier = Objects.requireNonNull(publisherSupplier, "publisherSupplier must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public RelayBatchResult relayBatch(String owner, int batchSize, Duration leaseDuration) {
        OutboxMessagePublisher publisher = publisherSupplier.get();
        if (publisher == null) {
            return RelayBatchResult.unavailable();
        }

        var claims = claimService.claim(owner, batchSize, leaseDuration);
        int published = 0;
        int failed = 0;
        int ownershipLost = 0;

        for (OutboxClaim claim : claims) {
            try {
                publisher.publish(toMessage(claim));
                if (claimService.markPublished(claim.id(), claim.leaseToken(), Instant.now(clock))) {
                    published++;
                } else {
                    ownershipLost++;
                }
            } catch (Exception exception) {
                Duration delay = retryPolicy.delayForAttempt(claim.deliveryAttempt());
                if (claimService.reschedule(
                        claim.id(),
                        claim.leaseToken(),
                        delay,
                        failureMessage(exception),
                        Instant.now(clock)
                )) {
                    failed++;
                } else {
                    ownershipLost++;
                }
            }
        }
        return new RelayBatchResult(claims.size(), published, failed, ownershipLost);
    }

    private OutboxMessage toMessage(OutboxClaim claim) {
        return new OutboxMessage(
                claim.id(),
                claim.eventKey(),
                claim.aggregateId(),
                claim.aggregateType(),
                claim.eventType(),
                claim.payload(),
                claim.deliveryAttempt()
        );
    }

    private String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
