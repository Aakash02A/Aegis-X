/**
 * Compliance Module
 * Advanced transaction search and filtering
 */

class Compliance {
    /**
     * Render compliance search page
     */
    static renderCompliancePage() {
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
                                <a class="nav-link" href="#dashboard">Dashboard</a>
                            </li>
                            <li class="nav-item">
                                <a class="nav-link active" href="#compliance">Compliance</a>
                            </li>
                            <li class="nav-item">
                                <a class="nav-link" href="#operations">Operations</a>
                            </li>
                            <li class="nav-item">
                                <a class="nav-link" href="#" onclick="Auth.logout(); return false;">Logout</a>
                            </li>
                        </ul>
                    </div>
                </div>
            </nav>

            <div class="container-fluid p-4">
                <div class="row mb-4">
                    <div class="col-md-12">
                        <h1>Compliance Search</h1>
                        <p class="text-muted">Advanced filtering and transaction discovery</p>
                    </div>
                </div>

                <div class="row mb-4">
                    <div class="col-md-12">
                        <div class="card">
                            <div class="card-header">
                                <h5 class="mb-0">Filters</h5>
                            </div>
                            <div class="card-body">
                                <form id="complianceFilterForm">
                                    <div class="row">
                                        <div class="col-md-3">
                                            <label class="form-label">Status</label>
                                            <select class="form-select" id="filterStatus">
                                                <option value="">All</option>
                                                <option value="CREATED">Created</option>
                                                <option value="PAYMENT_RECEIVED">Payment Received</option>
                                                <option value="EVIDENCE_PENDING">Evidence Pending</option>
                                                <option value="OCR_PROCESSING">OCR Processing</option>
                                                <option value="AI_ANALYSIS">AI Analysis</option>
                                                <option value="POLICY_EVALUATION">Policy Evaluation</option>
                                                <option value="APPROVED">Approved</option>
                                                <option value="COMPLIANCE_HOLD">On Hold</option>
                                            </select>
                                        </div>

                                        <div class="col-md-3">
                                            <label class="form-label">Currency</label>
                                            <select class="form-select" id="filterCurrency">
                                                <option value="">All</option>
                                                <option value="USD">USD</option>
                                                <option value="EUR">EUR</option>
                                                <option value="GBP">GBP</option>
                                            </select>
                                        </div>

                                        <div class="col-md-3">
                                            <label class="form-label">Amount Min</label>
                                            <input type="number" class="form-control" id="filterAmountMin" placeholder="0">
                                        </div>

                                        <div class="col-md-3">
                                            <label class="form-label">Amount Max</label>
                                            <input type="number" class="form-control" id="filterAmountMax" placeholder="999999">
                                        </div>
                                    </div>

                                    <div class="row mt-3">
                                        <div class="col-md-3">
                                            <label class="form-label">Date From</label>
                                            <input type="date" class="form-control" id="filterDateFrom">
                                        </div>

                                        <div class="col-md-3">
                                            <label class="form-label">Date To</label>
                                            <input type="date" class="form-control" id="filterDateTo">
                                        </div>

                                        <div class="col-md-3">
                                            <label class="form-label">Reference</label>
                                            <input type="text" class="form-control" id="filterReference" placeholder="TXN-...">
                                        </div>

                                        <div class="col-md-3">
                                            <label class="form-label">Purpose Code</label>
                                            <input type="text" class="form-control" id="filterPurpose" placeholder="e.g., SUPPLY">
                                        </div>
                                    </div>

                                    <div class="row mt-3">
                                        <div class="col-md-12">
                                            <button type="button" class="btn btn-primary" onclick="Compliance.search()">Search</button>
                                            <button type="reset" class="btn btn-outline-secondary">Clear</button>
                                        </div>
                                    </div>
                                </form>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="row">
                    <div class="col-md-12">
                        <div class="card">
                            <div class="card-header">
                                <h5 class="mb-0">Results</h5>
                            </div>
                            <div class="card-body">
                                <div id="complianceResults" class="spinner-border" role="status">
                                    <span class="visually-hidden">Loading...</span>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Execute search with current filters
     */
    static async search() {
        try {
            const filters = {
                status: document.getElementById('filterStatus').value || undefined,
                currency: document.getElementById('filterCurrency').value || undefined,
                amountMin: document.getElementById('filterAmountMin').value || undefined,
                amountMax: document.getElementById('filterAmountMax').value || undefined,
                dateFrom: document.getElementById('filterDateFrom').value || undefined,
                dateTo: document.getElementById('filterDateTo').value || undefined,
                reference: document.getElementById('filterReference').value || undefined,
                purposeCode: document.getElementById('filterPurpose').value || undefined,
                page: 0,
                size: 50
            };

            // Remove empty filters
            Object.keys(filters).forEach(key => {
                if (!filters[key]) delete filters[key];
            });

            const resultsContainer = document.getElementById('complianceResults');
            resultsContainer.innerHTML = '<div class="spinner-border" role="status"></div>';

            const response = await api.searchTransactions(filters);

            this.renderResults(response);
        } catch (error) {
            document.getElementById('complianceResults').innerHTML = 
                `<div class="alert alert-danger">Search error: ${error.message}</div>`;
        }
    }

    /**
     * Render search results
     */
    static renderResults(response) {
        const container = document.getElementById('complianceResults');

        if (!response.content || response.content.length === 0) {
            container.innerHTML = '<p class="text-muted">No transactions found matching your criteria</p>';
            return;
        }

        let html = `
            <p class="text-muted mb-3">Found ${response.totalElements} transaction(s)</p>
            <table class="table table-hover">
                <thead class="table-light">
                    <tr>
                        <th>Reference</th>
                        <th>Merchant</th>
                        <th>Amount</th>
                        <th>Status</th>
                        <th>Docs</th>
                        <th>Notes</th>
                        <th>Date</th>
                        <th>Action</th>
                    </tr>
                </thead>
                <tbody>
        `;

        response.content.forEach(txn => {
            html += `
                <tr>
                    <td><strong>${txn.referenceNumber}</strong></td>
                    <td>${txn.merchantEmail}</td>
                    <td>${txn.currency} ${txn.amount}</td>
                    <td><span class="badge ${Dashboard.getStatusBadgeClass(txn.status)}">${txn.status}</span></td>
                    <td>${txn.documentCount}</td>
                    <td>${txn.noteCount}</td>
                    <td>${new Date(txn.createdAt).toLocaleDateString()}</td>
                    <td>
                        <a href="#transaction/${txn.id}" class="btn btn-sm btn-outline-primary">View</a>
                    </td>
                </tr>
            `;
        });

        html += `
                </tbody>
            </table>
        `;

        if (response.totalPages > 1) {
            html += `<p class="text-muted">Page 1 of ${response.totalPages}</p>`;
        }

        container.innerHTML = html;
    }
}
