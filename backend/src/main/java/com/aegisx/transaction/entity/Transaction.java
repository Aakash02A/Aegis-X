package com.aegisx.transaction.entity;

import com.aegisx.auth.entity.User;
import com.aegisx.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "transactions")
public class Transaction extends BaseEntity {

    @Column(name = "reference_number", nullable = false, unique = true, length = 64)
    private String referenceNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", nullable = false)
    private User merchant;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "beneficiary_name", nullable = false, length = 255)
    private String beneficiaryName;

    @Column(name = "purpose_code", nullable = false, length = 50)
    private String purposeCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @lombok.Setter(lombok.AccessLevel.NONE)
    private TransactionStatus status;
    
    @Column(name = "is_terminal", nullable = false)
    @Builder.Default
    private Boolean isTerminal = false;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Controlled status mutation strictly intended for TransactionStateMachine.
     */
    public void applyStatus(TransactionStatus newStatus) {
        this.status = newStatus;
    }
    
    /**
     * Marks this transaction as terminal, preventing any further state transitions.
     * Only called by TransactionStateMachine when transitioning to a terminal state.
     */
    public void markAsTerminal() {
        this.isTerminal = true;
    }
    
    /**
     * Returns true if this transaction is in a terminal state.
     */
    public boolean isTerminal() {
        return Boolean.TRUE.equals(this.isTerminal);
    }

    /** Compatibility accessor retained for modules using the earlier transaction contract. */
    public UUID getMerchantId() { return merchant == null ? null : merchant.getId(); }

    /** Compatibility accessor retained for modules using the earlier transaction contract. */
    public String getPurpose() { return purposeCode; }
}

