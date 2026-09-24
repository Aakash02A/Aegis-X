/**
 * Operations Module
 * Prometheus metrics and system monitoring
 */

class Operations {
    /**
     * Render operations page
     */
    static renderOperationsPage() {
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
                                <a class="nav-link active" href="#operations">Operations</a>
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
                        <h1>Operations & Metrics</h1>
                        <p class="text-muted">System health and performance monitoring</p>
                    </div>
                </div>

                <div class="row">
                    <div class="col-md-6">
                        <div class="card">
                            <div class="card-header">
                                <h5 class="mb-0">System Metrics</h5>
                            </div>
                            <div class="card-body">
                                <div id="metricsContent" class="spinner-border"></div>
                            </div>
                        </div>
                    </div>

                    <div class="col-md-6">
                        <div class="card">
                            <div class="card-header">
                                <h5 class="mb-0">JVM Metrics</h5>
                            </div>
                            <div class="card-body">
                                <div id="jvmMetrics">
                                    <p class="text-muted">JVM metrics would be displayed here</p>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="row mt-4">
                    <div class="col-md-12">
                        <div class="card">
                            <div class="card-header">
                                <h5 class="mb-0">Raw Prometheus Metrics</h5>
                            </div>
                            <div class="card-body">
                                <button class="btn btn-sm btn-outline-primary mb-3" onclick="Operations.refreshMetrics()">Refresh</button>
                                <pre id="rawMetrics" style="max-height: 400px; overflow-y: auto; background: #f8f9fa; padding: 10px; border-radius: 4px;">
Loading...
                                </pre>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Load and display metrics
     */
    static async loadMetrics() {
        try {
            const metricsText = await api.getMetrics();
            
            // Parse Prometheus metrics
            const metrics = this.parseMetrics(metricsText);
            this.displayMetrics(metrics);
            
            // Display raw metrics
            document.getElementById('rawMetrics').textContent = metricsText.substring(0, 2000) + 
                (metricsText.length > 2000 ? '\n\n... (truncated) ...' : '');
        } catch (error) {
            document.getElementById('metricsContent').innerHTML = 
                `<div class="alert alert-warning">Error loading metrics: ${error.message}</div>`;
            document.getElementById('rawMetrics').textContent = 'Failed to load metrics';
        }
    }

    /**
     * Parse Prometheus metrics text
     */
    static parseMetrics(metricsText) {
        const metrics = {};
        const lines = metricsText.split('\n');

        lines.forEach(line => {
            if (line.startsWith('#') || line.trim() === '') return;

            const match = line.match(/(\w+(?:_\w+)*)\{?([^}]*)\}?\s+([\d.]+)/);
            if (match) {
                const [, name, labels, value] = match;
                if (!metrics[name]) metrics[name] = [];
                metrics[name].push({ labels, value: parseFloat(value) });
            }
        });

        return metrics;
    }

    /**
     * Display parsed metrics
     */
    static displayMetrics(metrics) {
        let html = '<table class="table table-sm"><tbody>';

        // Display selected key metrics
        const keyMetrics = [
            'jvm_memory_used_bytes',
            'jvm_threads_live',
            'jvm_gc_collection_seconds_count',
            'http_requests_total',
            'http_request_duration_seconds_sum'
        ];

        keyMetrics.forEach(metricName => {
            if (metrics[metricName]) {
                const values = metrics[metricName];
                values.forEach(({ labels, value }) => {
                    html += `
                        <tr>
                            <td><strong>${metricName}</strong></td>
                            <td>${labels || '-'}</td>
                            <td>${value.toFixed(2)}</td>
                        </tr>
                    `;
                });
            }
        });

        html += '</tbody></table>';
        document.getElementById('metricsContent').innerHTML = html;
    }

    /**
     * Refresh metrics
     */
    static async refreshMetrics() {
        await this.loadMetrics();
    }
}
