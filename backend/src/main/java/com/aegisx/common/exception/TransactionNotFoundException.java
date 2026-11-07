package com.aegisx.common.exception;

import java.util.UUID;

public class TransactionNotFoundException extends IllegalArgumentException {

    public TransactionNotFoundException(String message) {
        super(message);
    }

    public TransactionNotFoundException(UUID transactionId) {
        super("Transaction not found with ID: " + transactionId);
    }
}
