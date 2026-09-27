package com.aegisx.common.exception;

import com.aegisx.transaction.entity.TransactionStatus;
import lombok.Getter;

@Getter
public class IllegalTransactionStateException extends RuntimeException {

    private final TransactionStatus currentStatus;
    private final TransactionStatus targetStatus;

    public IllegalTransactionStateException(TransactionStatus currentStatus, TransactionStatus targetStatus) {
        super(String.format("Illegal transaction status transition from '%s' to '%s'", currentStatus, targetStatus));
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }
    
    public IllegalTransactionStateException(TransactionStatus currentStatus, TransactionStatus targetStatus, String customReason) {
        super(String.format("Illegal transaction status transition from '%s' to '%s': %s", currentStatus, targetStatus, customReason));
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public IllegalTransactionStateException(String message) {
        super(message);
        this.currentStatus = null;
        this.targetStatus = null;
    }
}
