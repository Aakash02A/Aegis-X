package com.aegisx.policy.rules;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.document.entity.RequiredDocument;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DocumentCompletenessRule verifies that all required documents are present.
 * 
 * Required documents for export transactions:
 * - INVOICE: Proof of goods/services
 * - SHIPPING_PROOF: Proof of delivery/transit
 * - EXPORT_DECLARATION: Regulatory compliance proof
 * 
 * Result:
 * - PASS: All three document types are fulfilled
 * - FAIL: Any required document is missing
 */
@Component
public class DocumentCompletenessRule implements PolicyRule {
    
    private static final String RULE_NAME = "DOCUMENT_COMPLETENESS";
    private static final List<DocumentType> REQUIRED_TYPES = List.of(
        DocumentType.INVOICE,
        DocumentType.SHIPPING_PROOF,
        DocumentType.EXPORT_DECLARATION
    );
    
    @Override
    public RuleEvaluationResult evaluate(RuleContext context) {
        if (context.getRequiredDocuments() == null || context.getRequiredDocuments().isEmpty()) {
            return RuleEvaluationResult.builder()
                .ruleName(RULE_NAME)
                .passed(false)
                .reason("No required documents checklist found")
                .evidence("RequiredDocuments list is empty or null")
                .build();
        }
        
        Set<DocumentType> fulfilledTypes = context.getRequiredDocuments().stream()
            .filter(RequiredDocument::isFulfilled)
            .map(RequiredDocument::getDocumentType)
            .collect(Collectors.toSet());
        
        List<DocumentType> missingTypes = REQUIRED_TYPES.stream()
            .filter(type -> !fulfilledTypes.contains(type))
            .toList();
        
        if (missingTypes.isEmpty()) {
            return RuleEvaluationResult.builder()
                .ruleName(RULE_NAME)
                .passed(true)
                .reason("All required documents present")
                .evidence("Invoice: present, Shipping Proof: present, Export Declaration: present")
                .build();
        } else {
            String missingList = missingTypes.stream()
                .map(DocumentType::name)
                .collect(Collectors.joining(", "));
            return RuleEvaluationResult.builder()
                .ruleName(RULE_NAME)
                .passed(false)
                .reason("Required documents missing")
                .evidence("Missing: " + missingList)
                .build();
        }
    }
    
    @Override
    public String getRuleName() {
        return RULE_NAME;
    }
}
