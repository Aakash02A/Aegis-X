package com.aegisx.policy.rules;

import com.aegisx.document.entity.DocumentExtraction;
import com.aegisx.document.entity.RequiredDocument;
import com.aegisx.transaction.entity.Transaction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RuleContext is the immutable container for all data needed by rules during evaluation.
 * 
 * Design:
 * - Single data transfer object passed to all rules
 * - All data loaded once before rule execution; no DB calls inside rules
 * - Pure function semantics: rules cannot modify context
 * - Builder pattern for convenient construction
 * 
 * Purpose:
 * - Enable deterministic rule execution
 * - Simplify rule implementation (single parameter)
 * - Centralize data validation before rules run
 * - Support testing and debugging
 * 
 * Data included:
 * - Transaction: amount, currency, purpose, beneficiary, status
 * - Required documents: invoice, shipping proof, export declaration (fulfilled status)
 * - OCR extraction: structured fields read from the document
 *
 * <p>AI output is deliberately absent. Extraction confidence remains an entity metadata field, but
 * no policy rule reads it; the verdict is based on the transaction, required-document checklist,
 * and selected structured OCR values.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleContext {
    
    private Transaction transaction;
    private List<RequiredDocument> requiredDocuments;
    private DocumentExtraction documentExtraction;
}
