package com.aegisx.integrity.provider;

import com.aegisx.integrity.domain.ProviderMode;
import com.aegisx.integrity.exception.IntegrityConflictException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderAdapterRegistryTest {

    @Test
    void historicalSimulatedModeHasNoRuntimeAdapter() {
        ProviderAdapterRegistry registry = new ProviderAdapterRegistry(List.of());

        assertThat(registry.isRegistered(ProviderMode.SIMULATED)).isFalse();
        assertThatThrownBy(() -> registry.forMode(ProviderMode.SIMULATED))
                .isInstanceOf(IntegrityConflictException.class)
                .hasMessageContaining("historical provider mode");
    }
}
