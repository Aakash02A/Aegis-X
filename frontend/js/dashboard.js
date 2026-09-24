/**
 * Dashboard Module
 * Merchant and Compliance Officer dashboard
 */

class Dashboard {
    /**
     * Render dashboard based on user role
     */
    static renderDashboard() {
        const user = Auth.getCurrentUser();
        
        if (user.role === 'MERCHANT') {
            return this.renderMerchantDashboard(user);
        } else if (user.role === 'COMPLIANCE_OFFICER' || user.role === 'ADMIN') {
            return this.renderComplianceDashboard(user);
        }
        
        return '<div class="alert alert-danger">Unknown user role</div>';
    }

    /**
     * Merchant dashboard - shows own transactions
     */
    static renderMerchantDashboard(user) {
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
                                <span class="nav-link">👤 ${user.email}</span>
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
                        <h1>My Transactions</h1>
                        <p class="text-muted">View and manage your submitted transactions</p>
                    </div>
                </div>

                <div class="row">
                    <div class="col-md-12">
                        <div class="card">
                            <div class="card-body">
                                <div id="merchantTransactionsList" class="spinner-border" role="status">
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
     * Compliance Officer dashboard - shows all transactions and search
     */
    static renderComplianceDashboard(user) {
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
                                <a class="nav-link" href="#compliance">Compliance</a>
                            </li>
                            <li class="nav-item">
                                <a class="nav-link" href="#operations">Operations</a>
                            </li>
                            <li class="nav-item">
                                <span class="nav-link">👤 ${user.email}</span>
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
                        <h1>Compliance Dashboard</h1>
                        <p class="text-muted">Transaction overview and search</p>
                    </div>
                </div>

                <div class="row mb-4">
                    <div class="col-md-3">
                        <div class="card bg-primary text-white">
                            <div class="card-body">
                                <h5 class="card-title">Total Transactions</h5>
                                <h3 id="totalTxns">-</h3>
                            </div>
                        </div>
                    </div>
                    <div class="col-md-3">
                        <div class="card bg-warning text-white">
                            <div class="card-body">
                                <h5 class="card-title">In Progress</h5>
                                <h3 id="inProgressTxns">-</h3>
                            </div>
                        </div>
                    </div>
                    <div class="col-md-3">
                        <div class="card bg-success text-white">
                            <div class="card-body">
                                <h5 class="card-title">Approved</h5>
                                <h3 id="approvedTxns">-</h3>
                            </div>
                        </div>
                    </div>
                    <div class="col-md-3">
                        <div class="card bg-danger text-white">
                            <div class="card-body">
                                <h5 class="card-title">On Hold</h5>
                                <h3 id="holdTxns">-</h3>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="row">
                    <div class="col-md-12">
                        <div class="card">
                            <div class="card-header">
                                <h5 class="mb-0">Recent Transactions</h5>
                            </div>
                            <div class="card-body">
                                <div id="complianceTransactionsList" class="spinner-border" role="status">
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
     * Load merchant transactions
     */
    static async loadMerchantTransactions() {
        try {
            const response = await api.searchTransactions({ page: 0, size: 50 });
            const container = document.getElementById('merchantTransactionsList');
            
            if (!response.content || response.content.length === 0) {
                container.innerHTML = '<p class="text-muted">No transactions yet</p>';
                return;
            }

            let html = `
                <table class="table table-hover">
                    <thead class="table-light">
                        <tr>
                            <th>Reference</th>
                            <th>Amount</th>
                            <th>Status</th>
                            <th>Date</th>
                            <th>Action</th>
                        </tr>
                    </thead>
                    <tbody>
            `;

            response.content.forEach(txn => {
                html += `
                    <tr>
                        <td>${txn.referenceNumber}</td>
                        <td>${txn.currency} ${txn.amount}</td>
                        <td><span class="badge bg-info">${txn.status}</span></td>
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

            container.innerHTML = html;
        } catch (error) {
            document.getElementById('merchantTransactionsList').innerHTML = 
                `<div class="alert alert-danger">Error loading transactions: ${error.message}</div>`;
        }
    }

    /**
     * Load compliance dashboard data
     */
    static async loadComplianceDashboard() {
        try {
            const response = await api.searchTransactions({ page: 0, size: 100 });
            
            // Calculate stats
            let total = response.totalElements || 0;
            let inProgress = 0;
            let approved = 0;
            let onHold = 0;

            response.content.forEach(txn => {
                if (['APPROVED'].includes(txn.status)) approved++;
                else if (['COMPLIANCE_HOLD'].includes(txn.status)) onHold++;
                else inProgress++;
            });

            // Update stats
            document.getElementById('totalTxns').textContent = total;
            document.getElementById('inProgressTxns').textContent = inProgress;
            document.getElementById('approvedTxns').textContent = approved;
            document.getElementById('holdTxns').textContent = onHold;

            // Render transactions table
            const container = document.getElementById('complianceTransactionsList');
            
            if (!response.content || response.content.length === 0) {
                container.innerHTML = '<p class="text-muted">No transactions</p>';
                return;
            }

            let html = `
                <table class="table table-hover">
                    <thead class="table-light">
                        <tr>
                            <th>Reference</th>
                            <th>Merchant</th>
                            <th>Amount</th>
                            <th>Status</th>
                            <th>Docs</th>
                            <th>Date</th>
                            <th>Action</th>
                        </tr>
                    </thead>
                    <tbody>
            `;

            response.content.slice(0, 20).forEach(txn => {
                html += `
                    <tr>
                        <td>${txn.referenceNumber}</td>
                        <td>${txn.merchantEmail}</td>
                        <td>${txn.currency} ${txn.amount}</td>
                        <td><span class="badge ${Dashboard.getStatusBadgeClass(txn.status)}">${txn.status}</span></td>
                        <td>${txn.documentCount}</td>
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

            container.innerHTML = html;
        } catch (error) {
            document.getElementById('complianceTransactionsList').innerHTML = 
                `<div class="alert alert-danger">Error loading dashboard: ${error.message}</div>`;
        }
    }

    /**
     * Get Bootstrap badge class for transaction status
     */
    static getStatusBadgeClass(status) {
        const statusMap = {
            'CREATED': 'bg-secondary',
            'PAYMENT_RECEIVED': 'bg-info',
            'EVIDENCE_PENDING': 'bg-warning text-dark',
            'OCR_PROCESSING': 'bg-info',
            'AI_ANALYSIS': 'bg-info',
            'POLICY_EVALUATION': 'bg-primary',
            'APPROVED': 'bg-success',
            'COMPLIANCE_HOLD': 'bg-danger'
        };
        return statusMap[status] || 'bg-secondary';
    }
}
