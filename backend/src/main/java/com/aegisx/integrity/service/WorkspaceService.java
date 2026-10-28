package com.aegisx.integrity.service;

import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.domain.CapabilityStatus;
import com.aegisx.integrity.domain.IntegrityEnvironment;
import com.aegisx.integrity.domain.ProviderMode;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.exception.IntegrityConflictException;
import com.aegisx.integrity.exception.IntegrityNotFoundException;
import com.aegisx.integrity.provider.PaymentProviderAdapter;
import com.aegisx.integrity.provider.ProviderAdapterRegistry;
import com.aegisx.integrity.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Module A: workspace and integration configuration.
 *
 * <p>The only environment value is {@code TEST}, so there is no code path that can point the engine
 * at live money. Credentials are never read here beyond the presence check performed by the adapter;
 * the workspace row stores only a masked key hint.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    public static final String DEFAULT_WORKSPACE_NAME = "Aegis-X Default Workspace";

    private final WorkspaceRepository repository;
    private final ProviderAdapterRegistry adapterRegistry;
    private final IntegrityProperties properties;

    @Transactional
    public Workspace create(String name, ProviderMode mode) {
        repository.findByName(name).ifPresent(existing -> {
            throw new IntegrityConflictException("A workspace named '" + name + "' already exists");
        });
        ProviderMode resolvedMode = mode == null ? ProviderMode.RAZORPAY_TEST : mode;
        if (resolvedMode != ProviderMode.RAZORPAY_TEST) {
            throw new IntegrityConflictException("New workspaces must use Razorpay Test Mode");
        }
        Workspace workspace = Workspace.builder()
                .name(name)
                .environment(IntegrityEnvironment.TEST)
                .providerMode(resolvedMode)
                .defaultCurrency(properties.getDefaultCurrency())
                .stalenessThresholdSeconds(properties.getStalenessThreshold().toSeconds())
                .build();
        workspace = repository.save(workspace);
        return refreshCapabilities(workspace.getId());
    }

    @Transactional
    public Workspace ensureDefaultWorkspace() {
        return repository.findByName(DEFAULT_WORKSPACE_NAME)
                .orElseGet(() -> {
                    Workspace workspace = repository.save(Workspace.builder()
                            .name(DEFAULT_WORKSPACE_NAME)
                            .environment(IntegrityEnvironment.TEST)
                            .providerMode(ProviderMode.RAZORPAY_TEST)
                            .defaultCurrency(properties.getDefaultCurrency())
                            .stalenessThresholdSeconds(properties.getStalenessThreshold().toSeconds())
                            .build());
                    return refreshCapabilities(workspace.getId());
                });
    }

    @Transactional(readOnly = true)
    public Workspace get(UUID workspaceId) {
        return repository.findById(workspaceId)
                .orElseThrow(() -> new IntegrityNotFoundException("Workspace " + workspaceId + " does not exist"));
    }

    @Transactional(readOnly = true)
    public List<Workspace> list() {
        return repository.findAllByOrderByCreatedAtAsc();
    }

    /**
     * Probes the provider and records the non-secret capability result on the workspace.
     *
     * <p>Only the resulting status is stored. No credential is read back, logged or returned.</p>
     */
    @Transactional
    public Workspace refreshCapabilities(UUID workspaceId) {
        Workspace workspace = get(workspaceId);
        if (!adapterRegistry.isRegistered(workspace.getProviderMode())) {
            workspace.setProviderConfigured(false);
            workspace.setTransferCapability(CapabilityStatus.NOT_CONFIGURED);
            return repository.save(workspace);
        }
        PaymentProviderAdapter adapter = adapterRegistry.forMode(workspace.getProviderMode());
        CapabilityStatus connectivity = adapter.connectivity();
        CapabilityStatus transfers = adapter.transferCapability();

        workspace.setProviderConfigured(connectivity == CapabilityStatus.OK
                || connectivity == CapabilityStatus.SIMULATED);
        workspace.setTransferCapability(transfers);
        workspace.setProviderKeyIdMasked(adapter.isSimulated() ? "simulated" : properties.getRazorpay().maskedKeyId());
        return repository.save(workspace);
    }

    @Transactional
    public Workspace updateStalenessThreshold(UUID workspaceId, long seconds) {
        if (seconds < 30) {
            throw new IntegrityConflictException("Staleness threshold must be at least 30 seconds");
        }
        Workspace workspace = get(workspaceId);
        workspace.setStalenessThresholdSeconds(seconds);
        return repository.save(workspace);
    }
}
