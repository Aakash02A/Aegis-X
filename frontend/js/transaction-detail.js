/**
 * Transaction Detail Module
 * 7-panel transaction workspace: Summary, Evidence, OCR, AI, Policy, Audit, Notes
 */

class TransactionDetail {
    /**
     * Render transaction detail page
     */
    static renderTransactionPage(txnId) {
        return `
            <nav class="navbar navbar-expand-lg navbar-light bg-light border-bottom">
                <div class="container-fluid">
                    <a class="navbar-brand fw-bold" href="#dashboard">Aegis-X</a>
                    <button class="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#navbarNav">
                        <span class="navbar-toggler-icon"></span>
                    </button>
                    <div class="collapse navbar-collapse" id="navbarNav">
                        <ul class="navbar-nav ms-auto">
                            <li class="nav-item">
                                <a class="nav-link" href="#dashboard">Back to Dashboard</a>
                            </li>
                            <li class="nav-item">
                                <a class="nav-link" href="#" onclick="Auth.logout(); return false;">Logout</a>
                            </li>
                        </ul>
                    </div>
                </div>
            </nav>

            <div class="container-fluid p-4">
                <ul class="nav nav-tabs mb-4" role="tablist">
                    <li class="nav-item" role="presentation">
                        <button class="nav-link active" id="summary-tab" data-bs-toggle="tab" data-bs-target="#summary" type="button" role="tab">Summary</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link" id="evidence-tab" data-bs-toggle="tab" data-bs-target="#evidence" type="button" role="tab">Evidence</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link" id="ocr-tab" data-bs-toggle="tab" data-bs-target="#ocr" type="button" role="tab">OCR Results</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link" id="ai-tab" data-bs-toggle="tab" data-bs-target="#ai" type="button" role="tab">AI Analysis</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link" id="policy-tab" data-bs-toggle="tab" data-bs-target="#policy" type="button" role="tab">Policy</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link" id="audit-tab" data-bs-toggle="tab" data-bs-target="#audit" type="button" role="tab">Audit</button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link" id="notes-tab" data-bs-toggle="tab" data-bs-target="#notes" type="button" role="tab">Notes</button>
                    </li>
                </ul>

                <div class="tab-content">
                    <div class="tab-pane fade show active" id="summary" role="tabpanel">
                        <div id="summaryContent" class="spinner-border"></div>
                    </div>
                    <div class="tab-pane fade" id="evidence" role="tabpanel">
                        <div id="evidenceContent" class="spinner-border"></div>
                    </div>
                    <div class="tab-pane fade" id="ocr" role="tabpanel">
                        <div id="ocrContent" class="spinner-border"></div>
                    </div>
                    <div class="tab-pane fade" id="ai" role="tabpanel">
                        <div id="aiContent" class="spinner-border"></div>
                    </div>
                    <div class="tab-pane fade" id="policy" role="tabpanel">
                        <div id="policyContent" class="spinner-border"></div>
                    </div>
                    <div class="tab-pane fade" id="audit" role="tabpanel">
                        <div id="auditContent" class="spinner-border"></div>
                    </div>
                    <div class="tab-pane fade" id="notes" role="tabpanel">
                        <div id="notesContent" class="spinner-border"></div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Load transaction details and populate panels
     */
    static async loadTransaction(txnId) {
        try {
            const txn = await api.getTransaction(txnId);
            this.renderSummary(txn);
        } catch (error) {
            document.getElementById('summaryContent').innerHTML = 
                `<div class="alert alert-danger">Error loading transaction: ${error.message}</div>`;
        }
    }

    /**
     * Render summary panel
     */
    static renderSummary(txn) {
        const html = `
            <div class="card">
                <div class="card-body">
                    <div class="row mb-3">
                        <div class="col-md-6">
                            <h5>Transaction Details</h5>
                            <p><strong>Reference:</strong> ${txn.referenceNumber}</p>
                            <p><strong>Amount:</strong> ${txn.currency} ${txn.amount}</p>
                            <p><strong>Status:</strong> <span class="badge ${Dashboard.getStatusBadgeClass(txn.status)}">${txn.status}</span></p>
                            <p><strong>Merchant:</strong> ${txn.merchant?.email || 'N/A'}</p>
                        </div>
                        <div class="col-md-6">
                            <h5>Beneficiary</h5>
                            <p><strong>Name:</strong> ${txn.beneficiaryName}</p>
                            <p><strong>Purpose:</strong> ${txn.purposeCode}</p>
                            <p><strong>Created:</strong> ${new Date(txn.createdAt).toLocaleString()}</p>
                            <p><strong>Updated:</strong> ${new Date(txn.updatedAt).toLocaleString()}</p>
                        </div>
                    </div>
                </div>
            </div>
        `;
        document.getElementById('summaryContent').innerHTML = html;
    }

    /**
     * Load and render evidence panel
     */
    static async loadEvidence(txnId) {
        try {
            const documents = await api.getTransactionDocuments(txnId);
            let html = '<div class="card"><div class="card-body">';

            if (!documents || documents.length === 0) {
                html += '<p class="text-muted">No documents uploaded</p>';
            } else {
                html += '<h5>Uploaded Documents</h5><table class="table"><tbody>';
                documents.forEach(doc => {
                    html += `
                        <tr>
                            <td>${doc.documentType}</td>
                            <td>
                                <span class="badge bg-info">${doc.status}</span>
                            </td>
                            <td>${new Date(doc.uploadedAt).toLocaleDateString()}</td>
                        </tr>
                    `;
                });
                html += '</tbody></table>';
            }

            html += '</div></div>';
            document.getElementById('evidenceContent').innerHTML = html;
        } catch (error) {
            document.getElementById('evidenceContent').innerHTML = 
                `<div class="alert alert-warning">Error loading documents: ${error.message}</div>`;
        }
    }

    /**
     * Load and render OCR panel
     */
    static async loadOCR(txnId) {
        // Placeholder for OCR data
        const html = `
            <div class="card">
                <div class="card-body">
                    <p class="text-muted">OCR results would be displayed here</p>
                </div>
            </div>
        `;
        document.getElementById('ocrContent').innerHTML = html;
    }

    /**
     * Load and render AI Analysis panel
     */
    static async loadAI(txnId) {
        // Placeholder for AI analysis
        const html = `
            <div class="card">
                <div class="card-body">
                    <p class="text-muted">AI analysis results would be displayed here</p>
                </div>
            </div>
        `;
        document.getElementById('aiContent').innerHTML = html;
    }

    /**
     * Load and render Policy panel
     */
    static async loadPolicy(txnId) {
        // Placeholder for policy evaluation
        const html = `
            <div class="card">
                <div class="card-body">
                    <p class="text-muted">Policy evaluation results would be displayed here</p>
                </div>
            </div>
        `;
        document.getElementById('policyContent').innerHTML = html;
    }

    /**
     * Load and render Audit panel
     */
    static async loadAudit(txnId) {
        try {
            const events = await api.getTransactionAudit(txnId);
            let html = '<div class="card"><div class="card-body">';

            if (!events || events.length === 0) {
                html += '<p class="text-muted">No audit events</p>';
            } else {
                html += '<h5>Audit Trail</h5><div class="timeline">';
                events.forEach((event, idx) => {
                    html += `
                        <div class="mb-3">
                            <p><strong>${event.eventType}</strong></p>
                            <p class="text-muted">${new Date(event.timestamp).toLocaleString()}</p>
                            <p>${event.details || 'No details'}</p>
                        </div>
                    `;
                });
                html += '</div>';
            }

            html += '</div></div>';
            document.getElementById('auditContent').innerHTML = html;
        } catch (error) {
            document.getElementById('auditContent').innerHTML = 
                `<div class="alert alert-warning">Error loading audit trail: ${error.message}</div>`;
        }
    }

    /**
     * Load and render Notes panel
     */
    static async loadNotes(txnId) {
        try {
            const notes = await api.getTransactionNotes(txnId);
            let html = '<div class="card"><div class="card-body">';

            // Add note form
            html += `
                <h5 class="mb-3">Add Note</h5>
                <form id="addNoteForm">
                    <div class="mb-2">
                        <textarea class="form-control" id="noteContent" rows="3" placeholder="Add a note..." required></textarea>
                    </div>
                    <button type="button" class="btn btn-primary btn-sm" onclick="TransactionDetail.addNote('${txnId}')">Add Note</button>
                </form>
                <hr>
            `;

            if (!notes || notes.length === 0) {
                html += '<p class="text-muted">No notes yet</p>';
            } else {
                html += '<h5>Notes</h5>';
                notes.forEach(note => {
                    html += `
                        <div class="card bg-light mb-2">
                            <div class="card-body">
                                <p><strong>${note.author}</strong></p>
                                <p>${note.content}</p>
                                <p class="text-muted small">${new Date(note.createdAt).toLocaleString()}</p>
                            </div>
                        </div>
                    `;
                });
            }

            html += '</div></div>';
            document.getElementById('notesContent').innerHTML = html;
        } catch (error) {
            document.getElementById('notesContent').innerHTML = 
                `<div class="alert alert-warning">Error loading notes: ${error.message}</div>`;
        }
    }

    /**
     * Add a note to transaction
     */
    static async addNote(txnId) {
        try {
            const content = document.getElementById('noteContent').value;
            if (!content.trim()) {
                alert('Please enter a note');
                return;
            }

            await api.addTransactionNote(txnId, content);
            document.getElementById('noteContent').value = '';
            this.loadNotes(txnId);
        } catch (error) {
            alert(`Error adding note: ${error.message}`);
        }
    }
}
