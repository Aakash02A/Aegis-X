package com.aegisx.integrity.webhook;

import com.aegisx.integrity.entity.IntegrityWebhookEvent;
import com.aegisx.integrity.repository.IntegrityWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims a provider event id in a transaction of its own.
 *
 * <p>This is a separate bean with {@link Propagation#REQUIRES_NEW} on purpose, and it is not
 * decoration. Two facts make the separation load-bearing:</p>
 *
 * <ol>
 *   <li>A unique-constraint violation invalidates the transaction it happens in, and the two engines
 *       disagree about how far that reaches. MySQL rolls back only the failing statement;
 *       PostgreSQL aborts the whole transaction until it is rolled back, so every subsequent
 *       statement fails. A caller that catches the violation and then keeps writing — which is
 *       exactly what duplicate-safe webhook ingestion wants to do — can only be correct on both
 *       engines if the violation happened in a <em>different</em> transaction.</li>
 *   <li>Spring's transaction advice is applied by a proxy, so expressing this as a method on the
 *       calling service would be a self-invocation and would silently join the caller's
 *       transaction, reintroducing the bug without any visible sign.</li>
 * </ol>
 *
 * <p>When this throws, the inner transaction has rolled back completely, the caller's transaction is
 * suspended and untouched, and the caller may safely treat the delivery as a duplicate.</p>
 */
@Component
@RequiredArgsConstructor
public class IntegrityWebhookEventReserver {

    private final IntegrityWebhookEventRepository eventRepository;

    /**
     * Inserts the event row and flushes it, so the idempotency guard fires here instead of at the
     * caller's commit, where the outcome could no longer be recovered from.
     *
     * @throws DataIntegrityViolationException when another delivery of the same provider event id won
     *         the race. See the class comment for why the caller can continue afterwards.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IntegrityWebhookEvent reserve(IntegrityWebhookEvent event) {
        return eventRepository.saveAndFlush(event);
    }
}
