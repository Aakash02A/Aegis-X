/**
 * Manual review notes module for compliance officer investigation tracking.
 * 
 * This module allows compliance officers and admins to add investigation notes
 * to transactions, creating an immutable audit trail of the compliance review process.
 * 
 * Features:
 * - Append-only note creation
 * - Automatic audit event generation
 * - RBAC enforcement (compliance officers and admins only)
 * - Complete investigation history per transaction
 * - User activity tracking
 * 
 * Subpackages:
 * - entity: ManualReviewNote domain model
 * - repository: Data access for review notes
 * - dto: Request/response objects
 * - service: Business logic for note creation and retrieval
 * - controller: REST API endpoints
 * 
 * Key Design Principles:
 * 1. Notes are append-only and immutable
 * 2. Every note creation generates an audit event
 * 3. RBAC: Only compliance officers and admins can create/view notes
 * 4. Merchants can view notes on their own transactions
 * 5. Compliance officers can view notes on all transactions
 * 
 * Typical Usage Flow:
 * 1. Compliance officer opens transaction workspace
 * 2. Reviews uploaded documents and OCR extraction
 * 3. Reviews AI analysis and policy evaluation
 * 4. Adds note: "Investigation findings and recommendation"
 * 5. System creates audit event recording the note
 * 6. Note appears in audit timeline and compliance workspace
 */
package com.aegisx.review;
