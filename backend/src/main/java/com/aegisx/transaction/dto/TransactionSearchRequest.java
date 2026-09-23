package com.aegisx.transaction.dto;

import com.aegisx.transaction.entity.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request DTO for transaction search API.
 * Used to filter transactions with various criteria.
 * 
 * All fields are optional - if null, that filter is not applied.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionSearchRequest {

    /**
     * Filter by transaction status.
     * Optional - if null, all statuses included.
     */
    private TransactionStatus status;

    /**
     * Filter by merchant ID.
     * Optional - used by compliance officers to filter by specific merchant.
     * Merchants cannot specify this (RBAC applied at controller).
     */
    private UUID merchantId;

    /**
     * Filter by transaction reference number (partial match).
     * Optional - allows searching "TXN-20260922" to find all transactions from that date.
     */
    private String referenceNumber;

    /**
     * Filter by minimum amount (inclusive).
     * Optional - for range queries like "all transactions > $1,000".
     */
    private BigDecimal amountMin;

    /**
     * Filter by maximum amount (inclusive).
     * Optional - for range queries like "all transactions < $10,000".
     */
    private BigDecimal amountMax;

    /**
     * Filter by currency code (3-letter ISO, e.g., "USD").
     * Optional - filters for specific currency.
     */
    private String currency;

    /**
     * Filter by start date (inclusive).
     * Optional - for date range queries.
     */
    private LocalDate dateFrom;

    /**
     * Filter by end date (inclusive).
     * Optional - for date range queries.
     */
    private LocalDate dateTo;

    /**
     * Filter by beneficiary name (partial match).
     * Optional - allows searching for specific payees.
     */
    private String beneficiaryName;

    /**
     * Filter by purpose code.
     * Optional - filters by transaction purpose (e.g., "CONSULTING", "SUPPLY").
     */
    private String purposeCode;
}
