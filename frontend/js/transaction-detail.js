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
            const response = await api.getTransactionAudit(txnId);
            const container = document.getElementById('auditContent');
            
            // Handle both array and Page response formats
            const events = Array.isArray(response) ? response : (response.content || []);

            if (!events || events.length === 0) {
                container.innerHTML = '<p class="text-muted">No audit events</p>';
                return;
            }

            container.innerHTML = '<h5>Audit Trail</h5><div class="timeline" id="auditTimeline"></div>';
            const timeline = document.getElementById('auditTimeline');

            events.forEach((event, idx) => {
                const eventDiv = document.createElement('div');
                eventDiv.className = 'mb-3';
                
                // Build event title safely
                const titleEl = document.createElement('p');
                titleEl.innerHTML = `<strong>${this.escapeHtml(event.eventType || 'Event')}</strong> 
                                     <span class="badge bg-secondary">${this.escapeHtml(event.module || 'N/A')}</span>`;
                eventDiv.appendChild(titleEl);
                
                // Timestamp
                const timeEl = document.createElement('p');
                timeEl.className = 'text-muted';
                timeEl.textContent = new Date(event.createdAt).toLocaleString();
                eventDiv.appendChild(timeEl);
                
                // State transition (if present)
                if (event.previousState) {
                    const stateEl = document.createElement('p');
                    stateEl.textContent = `State: ${event.previousState} → ${event.newState}`;
                    eventDiv.appendChild(stateEl);
                }
                
                // Details (if present)
                if (event.details) {
                    const detailEl = document.createElement('p');
                    detailEl.textContent = event.details;
                    eventDiv.appendChild(detailEl);
                }
                
                timeline.appendChild(eventDiv);
            });
        } catch (error) {
            document.getElementById('auditContent').innerHTML = 
                `<div class="alert alert-warning">Error loading audit trail: ${error.message}</div>`;
        }
    }

    /**
     * Escape HTML special characters to prevent XSS
     */
    static escapeHtml(text) {
        if (!text) return '';
        const map = {
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#039;'
        };
        return text.replace(/[&<>"']/g, m => map[m]);
    }

    /**
     * Load and render Notes panel
     */
    static async loadNotes(txnId) {
        try {
            const notes = await api.getTransactionNotes(txnId);
            const container = document.getElementById('notesContent');
            
            // Create form section
            const formDiv = document.createElement('div');
            formDiv.className = 'card';
            formDiv.innerHTML = `
                <div class="card-body">
                    <h5 class="mb-3">Add Note</h5>
                    <textarea class="form-control" id="noteContent" rows="3" placeholder="Add a note..." required></textarea>
                    <button type="button" class="btn btn-primary btn-sm mt-2" onclick="TransactionDetail.addNote('${txnId}')">Add Note</button>
                </div>
            `;
            container.appendChild(formDiv);
            
            // Add notes section
            if (!notes || notes.content === undefined || notes.content.length === 0) {
                container.appendChild(document.createElement('p')).textContent = 'No notes yet';
                return;
            }

            const notesHeader = document.createElement('h5');
            notesHeader.textContent = 'Notes';
            container.appendChild(notesHeader);

            notes.content.forEach(note => {
                const noteCard = document.createElement('div');
                noteCard.className = 'card bg-light mb-2';
                
                const body = document.createElement('div');
                body.className = 'card-body';
                
                // Author line
                const authorLine = document.createElement('p');
                authorLine.innerHTML = `<strong>${this.escapeHtml(note.authorName || 'Unknown')}</strong> 
                                       <span class="text-muted small">(${this.escapeHtml(note.authorEmail || '')})</span>`;
                body.appendChild(authorLine);
                
                // Note text
                const textEl = document.createElement('p');
                textEl.textContent = note.noteText;
                body.appendChild(textEl);
                
                // Timestamp
                const timeEl = document.createElement('p');
                timeEl.className = 'text-muted small';
                timeEl.textContent = new Date(note.createdAt).toLocaleString();
                body.appendChild(timeEl);
                
                noteCard.appendChild(body);
                container.appendChild(noteCard);
            });
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

            // Backend expects { noteText } not { content }
            await api.addTransactionNote(txnId, content);
            document.getElementById('noteContent').value = '';
            this.loadNotes(txnId);
        } catch (error) {
            alert(`Error adding note: ${error.message}`);
        }
    }
}
