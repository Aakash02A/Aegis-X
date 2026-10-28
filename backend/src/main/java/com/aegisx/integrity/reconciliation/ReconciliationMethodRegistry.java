package com.aegisx.integrity.reconciliation;

import com.aegisx.integrity.domain.ReconciliationStrategy;
import com.aegisx.integrity.reconciliation.model.ReconciliationMethod;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Resolves the decision procedure for a reconciliation run, including the two benchmark baselines. */
@Component
public class ReconciliationMethodRegistry {

    private final Map<ReconciliationStrategy, ReconciliationMethod> byStrategy = new EnumMap<>(ReconciliationStrategy.class);

    public ReconciliationMethodRegistry(List<ReconciliationMethod> methods) {
        for (ReconciliationMethod method : methods) {
            ReconciliationMethod previous = byStrategy.put(method.strategy(), method);
            if (previous != null) {
                throw new IllegalStateException("Duplicate reconciliation method for " + method.strategy());
            }
        }
    }

    public ReconciliationMethod forStrategy(ReconciliationStrategy strategy) {
        ReconciliationMethod method = byStrategy.get(strategy);
        if (method == null) {
            throw new IllegalStateException("No reconciliation method registered for " + strategy);
        }
        return method;
    }

    public List<ReconciliationMethod> all() {
        return List.copyOf(byStrategy.values());
    }
}
