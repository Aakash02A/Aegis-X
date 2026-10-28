package com.aegisx.integrity.entity;

import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.CapabilityStatus;
import com.aegisx.integrity.domain.IntegrityEnvironment;
import com.aegisx.integrity.domain.ProviderMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Module A: merchant/workspace configuration.
 *
 * <p>Stores only non-secret integration metadata. Provider key ids are stored masked; provider
 * secrets never reach this table or any other row. {@code providerConfigured} records *whether*
 * credentials are present in the environment, not their value.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pi_workspaces")
public class Workspace extends BaseEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 20)
    private IntegrityEnvironment environment;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_mode", nullable = false, length = 30)
    private ProviderMode providerMode;

    /** Masked provider key id, for example {@code rzp_test_****abcd}. Never the raw id or secret. */
    @Column(name = "provider_key_id_masked", length = 40)
    private String providerKeyIdMasked;

    @Builder.Default
    @Column(name = "provider_configured", nullable = false)
    private boolean providerConfigured = false;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_capability", nullable = false, length = 20)
    private CapabilityStatus transferCapability = CapabilityStatus.NOT_CONFIGURED;

    @Builder.Default
    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency = "INR";

    /** Age after which a still-pending payment is considered stale. */
    @Builder.Default
    @Column(name = "staleness_threshold_seconds", nullable = false)
    private long stalenessThresholdSeconds = 900L;
}
