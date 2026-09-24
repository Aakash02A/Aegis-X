/**
 * App Module
 * Main router and navigation logic
 */

class App {
    static currentPage = null;

    /**
     * Initialize the application
     */
    static init() {
        this.setupRouting();
        this.route();
    }

    /**
     * Setup hash-based routing
     */
    static setupRouting() {
        window.addEventListener('hashchange', () => this.route());
    }

    /**
     * Route based on hash
     */
    static route() {
        const hash = window.location.hash.slice(1) || 'login';
        const [page, param] = hash.split('/');

        // Redirect to login if not authenticated
        if (page !== 'login' && page !== 'register' && !Auth.isAuthenticated()) {
            window.location.hash = '#login';
            return;
        }

        // Render page
        switch (page) {
            case 'login':
                this.renderLogin();
                break;
            case 'register':
                this.renderRegister();
                break;
            case 'dashboard':
                this.renderDashboard();
                break;
            case 'compliance':
                this.renderCompliance();
                break;
            case 'transaction':
                this.renderTransaction(param);
                break;
            case 'operations':
                this.renderOperations();
                break;
            default:
                window.location.hash = '#dashboard';
        }
    }

    /**
     * Render login page
     */
    static renderLogin() {
        const app = document.getElementById('app');
        app.innerHTML = Auth.renderLoginPage();

        const form = document.getElementById('loginForm');
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            const email = document.getElementById('email').value;
            const password = document.getElementById('password').value;
            Auth.handleLogin(email, password);
        });
    }

    /**
     * Render register page
     */
    static renderRegister() {
        const app = document.getElementById('app');
        app.innerHTML = Auth.renderRegisterPage();

        const form = document.getElementById('registerForm');
        form.addEventListener('submit', (e) => {
            e.preventDefault();
            const fullName = document.getElementById('fullName').value;
            const email = document.getElementById('regEmail').value;
            const password = document.getElementById('regPassword').value;
            Auth.handleRegister(fullName, email, password);
        });
    }

    /**
     * Render dashboard
     */
    static renderDashboard() {
        const app = document.getElementById('app');
        app.innerHTML = Dashboard.renderDashboard();

        const user = Auth.getCurrentUser();
        if (user.role === 'MERCHANT') {
            Dashboard.loadMerchantTransactions();
        } else {
            Dashboard.loadComplianceDashboard();
        }
    }

    /**
     * Render compliance search page
     */
    static renderCompliance() {
        const app = document.getElementById('app');
        app.innerHTML = Compliance.renderCompliancePage();
        Compliance.search();
    }

    /**
     * Render transaction detail page
     */
    static renderTransaction(txnId) {
        const app = document.getElementById('app');
        app.innerHTML = TransactionDetail.renderTransactionPage(txnId);

        // Load transaction data
        TransactionDetail.loadTransaction(txnId);

        // Setup tab event listeners
        setTimeout(() => {
            document.getElementById('evidence-tab')?.addEventListener('click', () => {
                TransactionDetail.loadEvidence(txnId);
            });

            document.getElementById('ocr-tab')?.addEventListener('click', () => {
                TransactionDetail.loadOCR(txnId);
            });

            document.getElementById('ai-tab')?.addEventListener('click', () => {
                TransactionDetail.loadAI(txnId);
            });

            document.getElementById('policy-tab')?.addEventListener('click', () => {
                TransactionDetail.loadPolicy(txnId);
            });

            document.getElementById('audit-tab')?.addEventListener('click', () => {
                TransactionDetail.loadAudit(txnId);
            });

            document.getElementById('notes-tab')?.addEventListener('click', () => {
                TransactionDetail.loadNotes(txnId);
            });
        }, 100);
    }

    /**
     * Render operations page
     */
    static renderOperations() {
        const app = document.getElementById('app');
        app.innerHTML = Operations.renderOperationsPage();
        Operations.loadMetrics();
    }
}

// Initialize app when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    App.init();
});
