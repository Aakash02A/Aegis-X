package com.aegisx.integrity.provider;

import com.aegisx.integrity.domain.ProviderMode;
import com.aegisx.integrity.exception.IntegrityConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the adapter for a workspace's provider mode.
 *
 * <p>Every adapter resolves by {@link PaymentProviderAdapter#mode()}, so adding a provider is a
 * matter of adding an implementation rather than touching call sites.</p>
 */
@Slf4j
@Component
public class ProviderAdapterRegistry {

    private final Map<ProviderMode, PaymentProviderAdapter> byMode = new EnumMap<>(ProviderMode.class);

    public ProviderAdapterRegistry(List<PaymentProviderAdapter> adapters) {
        for (PaymentProviderAdapter adapter : adapters) {
            PaymentProviderAdapter previous = byMode.put(adapter.mode(), adapter);
            if (previous != null) {
                throw new IllegalStateException("Duplicate adapter registered for mode " + adapter.mode());
            }
        }
        log.info("Integrity provider adapters registered: {}", byMode.keySet());
    }

    public PaymentProviderAdapter forMode(ProviderMode mode) {
        return Optional.ofNullable(byMode.get(mode))
                .orElseThrow(() -> new IntegrityConflictException(
                        "No runtime provider adapter is available for historical provider mode " + mode));
    }

    public boolean isRegistered(ProviderMode mode) {
        return byMode.containsKey(mode);
    }
}
