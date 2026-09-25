/**
 * Real-Time Updates Module
 * Handles periodic data refresh for dashboard components
 */

class RealTimeUpdates {
    constructor() {
        this.intervals = new Map();
        this.isActive = false;
        this.updateFrequency = {
            dashboard: 30000,    // 30 seconds
            transactions: 15000,  // 15 seconds
            metrics: 60000,       // 1 minute
            audit: 10000         // 10 seconds
        };
    }

    /**
     * Start real-time updates for current page
     */
    startUpdates(pageType, context = {}) {
        this.stopAllUpdates();
        this.isActive = true;

        switch (pageType) {
            case 'dashboard':
                this.startDashboardUpdates();
                break;
            case 'compliance':
                this.startComplianceUpdates();
                break;
            case 'operations':
                this.startOperationsUpdates();
                break;
            case 'transaction-detail':
                this.startTransactionDetailUpdates(context.transactionId);
                break;
        }

        console.log(`Real-time updates started for: ${pageType}`);
    }

    /**
     * Stop all active updates
     */
    stopAllUpdates() {
        this.intervals.forEach((intervalId, name) => {
            clearInterval(intervalId);
            console.log(`Stopped update: ${name}`);
        });
        this.intervals.clear();
        this.isActive = false;
    }

    /**
     * Start dashboard updates
     */
    startDashboardUpdates() {
        // Update dashboard stats periodically
        const dashboardInterval = setInterval(async () => {
            try {
                if (document.getElementById('complianceTransactionsList')) {
                    await Dashboard.loadComplianceDashboard();
                }
            } catch (error) {
                console.warn('Dashboard update failed:', error.message);
            }
        }, this.updateFrequency.dashboard);

        this.intervals.set('dashboard', dashboardInterval);

        // Update transaction counts more frequently
        const statsInterval = setInterval(async () => {
            try {
                await this.updateTransactionStats();
            } catch (error) {
                console.warn('Stats update failed:', error.message);
            }
        }, this.updateFrequency.transactions);

        this.intervals.set('stats', statsInterval);
    }

    /**
     * Start compliance page updates
     */
    startComplianceUpdates() {
        // Refresh search results periodically
        const complianceInterval = setInterval(async () => {
            try {
                if (typeof Compliance !== 'undefined') {
                    await Compliance.search();
                }
            } catch (error) {
                console.warn('Compliance update failed:', error.message);
            }
        }, this.updateFrequency.transactions);

        this.intervals.set('compliance', complianceInterval);
    }

    /**
     * Start operations updates  
     */
    startOperationsUpdates() {
        // Refresh metrics periodically
        const metricsInterval = setInterval(async () => {
            try {
                if (typeof Operations !== 'undefined') {
                    await Operations.loadMetrics();
                }
            } catch (error) {
                console.warn('Metrics update failed:', error.message);
            }
        }, this.updateFrequency.metrics);

        this.intervals.set('metrics', metricsInterval);
    }

    /**
     * Start transaction detail updates
     */
    startTransactionDetailUpdates(transactionId) {
        if (!transactionId) return;

        // Update audit trail frequently
        const auditInterval = setInterval(async () => {
            try {
                if (typeof TransactionDetail !== 'undefined') {
                    await TransactionDetail.loadAudit(transactionId);
                }
            } catch (error) {
                console.warn('Audit update failed:', error.message);
            }
        }, this.updateFrequency.audit);

        this.intervals.set('audit', auditInterval);

        // Update transaction summary less frequently
        const summaryInterval = setInterval(async () => {
            try {
                if (typeof TransactionDetail !== 'undefined') {
                    await TransactionDetail.loadTransaction(transactionId);
                }
            } catch (error) {
                console.warn('Transaction summary update failed:', error.message);
            }
        }, this.updateFrequency.dashboard);

        this.intervals.set('transaction-summary', summaryInterval);
    }

    /**
     * Update transaction statistics only
     */
    async updateTransactionStats() {
        try {
            const response = await api.searchTransactions({ page: 0, size: 100 });
            const allTransactions = response.content || [];
            
            // Calculate stats
            const stats = this.calculateQuickStats(allTransactions);
            
            // Update only the stat cards
            this.updateStatCards(stats, response.totalElements || 0);
            
        } catch (error) {
            console.warn('Failed to update transaction stats:', error.message);
        }
    }

    /**
     * Calculate quick statistics
     */
    calculateQuickStats(transactions) {
        const stats = {
            inProgress: 0,
            approved: 0,
            onHold: 0
        };

        transactions.forEach(txn => {
            const status = txn.status || 'UNKNOWN';
            
            if (['APPROVED'].includes(status)) {
                stats.approved++;
            } else if (['COMPLIANCE_HOLD'].includes(status)) {
                stats.onHold++;
            } else {
                stats.inProgress++;
            }
        });

        return stats;
    }

    /**
     * Update stat cards with animation
     */
    updateStatCards(stats, total) {
        this.animateCounterUpdate('totalTxns', total);
        this.animateCounterUpdate('inProgressTxns', stats.inProgress);
        this.animateCounterUpdate('approvedTxns', stats.approved);
        this.animateCounterUpdate('holdTxns', stats.onHold);
    }

    /**
     * Animate counter updates
     */
    animateCounterUpdate(elementId, newValue) {
        const element = document.getElementById(elementId);
        if (!element) return;

        const currentValue = parseInt(element.textContent) || 0;
        
        if (currentValue !== newValue) {
            // Add update animation class
            element.classList.add('counter-update');
            
            // Update the value
            element.textContent = newValue;
            
            // Remove animation class after animation
            setTimeout(() => {
                element.classList.remove('counter-update');
            }, 500);
        }
    }

    /**
     * Show update indicator
     */
    showUpdateIndicator(message = 'Updating...') {
        let indicator = document.getElementById('update-indicator');
        
        if (!indicator) {
            indicator = document.createElement('div');
            indicator.id = 'update-indicator';
            indicator.className = 'position-fixed top-0 end-0 m-3 alert alert-info alert-dismissible fade';
            indicator.style.zIndex = '9999';
            document.body.appendChild(indicator);
        }

        indicator.innerHTML = `
            <div class="d-flex align-items-center">
                <div class="spinner-border spinner-border-sm me-2" role="status"></div>
                ${message}
            </div>
        `;
        
        indicator.classList.add('show');
        
        // Auto-hide after 2 seconds
        setTimeout(() => {
            indicator.classList.remove('show');
        }, 2000);
    }

    /**
     * Check if updates are currently active
     */
    isUpdating() {
        return this.isActive && this.intervals.size > 0;
    }

    /**
     * Get current update status
     */
    getStatus() {
        return {
            active: this.isActive,
            intervals: Array.from(this.intervals.keys()),
            count: this.intervals.size
        };
    }

    /**
     * Pause updates temporarily
     */
    pauseUpdates() {
        if (this.isActive) {
            this.intervals.forEach((intervalId) => {
                clearInterval(intervalId);
            });
            this.intervals.clear();
            console.log('Real-time updates paused');
        }
    }

    /**
     * Resume updates
     */
    resumeUpdates(pageType, context = {}) {
        if (this.isActive) {
            this.startUpdates(pageType, context);
            console.log('Real-time updates resumed');
        }
    }
}

// Global instance
const realTimeUpdates = new RealTimeUpdates();

// Auto-cleanup on page unload
window.addEventListener('beforeunload', () => {
    realTimeUpdates.stopAllUpdates();
});

// Pause updates when tab becomes inactive
document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        realTimeUpdates.pauseUpdates();
    } else {
        // Resume updates when tab becomes active
        const currentPage = window.location.hash.substring(1) || 'dashboard';
        const context = {};
        
        // Extract context for transaction details
        if (currentPage.startsWith('transaction/')) {
            context.transactionId = currentPage.split('/')[1];
        }
        
        realTimeUpdates.resumeUpdates(currentPage.split('/')[0], context);
    }
});