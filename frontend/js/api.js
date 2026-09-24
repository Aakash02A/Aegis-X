/**
 * API Client Module
 * Handles all HTTP requests with Bearer token authentication and correlation IDs
 */

class ApiClient {
    constructor(baseUrl = 'http://localhost:8080/api') {
        this.baseUrl = baseUrl;
        this.authToken = localStorage.getItem('authToken');
        this.correlationId = this.generateCorrelationId();
    }

    /**
     * Generate a unique correlation ID for request tracking
     */
    generateCorrelationId() {
        return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
    }

    /**
     * Set auth token after login
     */
    setAuthToken(token) {
        this.authToken = token;
        localStorage.setItem('authToken', token);
    }

    /**
     * Clear auth token on logout
     */
    clearAuthToken() {
        this.authToken = null;
        localStorage.removeItem('authToken');
    }

    /**
     * Build headers with auth token and correlation ID
     */
    getHeaders() {
        const headers = {
            'Content-Type': 'application/json',
            'X-Correlation-ID': this.generateCorrelationId()
        };
        if (this.authToken) {
            headers['Authorization'] = `Bearer ${this.authToken}`;
        }
        return headers;
    }

    /**
     * Generic fetch wrapper with error handling
     */
    async request(endpoint, options = {}) {
        const url = `${this.baseUrl}${endpoint}`;
        const config = {
            ...options,
            headers: this.getHeaders()
        };

        try {
            const response = await fetch(url, config);

            // Handle 401 Unauthorized - token expired
            if (response.status === 401) {
                this.clearAuthToken();
                window.location.hash = '#login';
                throw new Error('Authentication expired. Please login again.');
            }

            // Handle other HTTP errors
            if (!response.ok) {
                const error = await response.text();
                throw new Error(`HTTP ${response.status}: ${error}`);
            }

            // Parse JSON response
            return await response.json();
        } catch (error) {
            console.error(`API Error [${endpoint}]:`, error);
            throw error;
        }
    }

    /**
     * GET request
     */
    get(endpoint, params = {}) {
        const queryString = new URLSearchParams(params).toString();
        const url = queryString ? `${endpoint}?${queryString}` : endpoint;
        return this.request(url, { method: 'GET' });
    }

    /**
     * POST request
     */
    post(endpoint, data = {}) {
        return this.request(endpoint, {
            method: 'POST',
            body: JSON.stringify(data)
        });
    }

    /**
     * PUT request
     */
    put(endpoint, data = {}) {
        return this.request(endpoint, {
            method: 'PUT',
            body: JSON.stringify(data)
        });
    }

    /**
     * DELETE request
     */
    delete(endpoint) {
        return this.request(endpoint, { method: 'DELETE' });
    }

    // =====================
    // Auth Endpoints
    // =====================

    async login(email, password) {
        const response = await this.post('/auth/login', { email, password });
        if (response.token) {
            this.setAuthToken(response.token);
        }
        return response;
    }

    async register(email, password, fullName) {
        const response = await this.post('/auth/register', { email, password, fullName });
        if (response.token) {
            this.setAuthToken(response.token);
        }
        return response;
    }

    // =====================
    // Transaction Endpoints
    // =====================

    async searchTransactions(filters = {}) {
        return this.get('/transactions/search', filters);
    }

    async getTransaction(id) {
        return this.get(`/transactions/${id}`);
    }

    async getTransactionDocuments(id) {
        return this.get(`/transactions/${id}/documents`);
    }

    async getTransactionAudit(id) {
        return this.get(`/transactions/${id}/audit`);
    }

    async getTransactionNotes(id) {
        return this.get(`/transactions/${id}/notes`);
    }

    async addTransactionNote(id, content) {
        return this.post(`/transactions/${id}/notes`, { content });
    }

    async updateTransactionNote(transactionId, noteId, content) {
        return this.put(`/transactions/${transactionId}/notes/${noteId}`, { content });
    }

    // =====================
    // Metrics Endpoints
    // =====================

    async getMetrics() {
        return fetch('http://localhost:8080/actuator/prometheus')
            .then(r => r.text())
            .catch(e => {
                console.error('Failed to fetch metrics:', e);
                return '';
            });
    }
}

// Create global instance
const api = new ApiClient();
