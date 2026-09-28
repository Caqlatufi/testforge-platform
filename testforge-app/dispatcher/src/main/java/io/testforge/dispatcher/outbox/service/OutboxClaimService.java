package io.testforge.dispatcher.outbox.service;

import io.testforge.dispatcher.outbox.model.OutboxClaim;
import io.testforge.dispatcher.outbox.repo.OutboxEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public class OutboxClaimService {

    private final OutboxEventRepository repository;
    private final Clock clock;
    private final Supplier<UUID> tokenGenerator;

    public OutboxClaimService(
            OutboxEventRepository repository,
            Clock clock,
            Supplier<UUID> tokenGenerator
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator must not be null");
    }

    @Transactional
    public List<OutboxClaim> claim(String owner, int batchSize, Duration leaseDuration) {
        if (owner == null || owner.isBlank() || owner.length() > 120) {
            throw new IllegalArgumentException("owner 必须为长度不超过 120 的非空字符串");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize 必须在 1 到 1000 之间");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration 必须大于 0");
        }

        Instant now = Instant.now(clock);
        return repository.findClaimableForUpdate(now, PageRequest.of(0, batchSize)).stream()
                .filter(event -> event.isClaimableAt(now))
                .map(event -> event.claim(owner, tokenGenerator.get(), now, leaseDuration))
                .toList();
    }

    @Transactional
    public boolean markPublished(UUID eventId, UUID leaseToken, Instant publishedAt) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        return repository.findByIdForUpdate(eventId)
                .map(event -> event.markPublished(leaseToken, publishedAt))
                .orElse(false);
    }

    @Transactional
    public boolean reschedule(UUID eventId, UUID leaseToken, Duration delay, String error, Instant failedAt) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay 不能小于 0");
        }
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        return repository.findByIdForUpdate(eventId)
                .map(event -> event.reschedule(leaseToken, failedAt, delay, error))
                .orElse(false);
    }
}
