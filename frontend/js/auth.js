/**
 * Auth Module
 * Handles login and registration pages
 * 
 * Security Notes:
 * - JWTs are stored in localStorage for MVP (not ideal for production)
 * - Production should use:
 *   1. HttpOnly secure cookies (cannot be accessed by JavaScript)
 *   2. Separate refresh token rotation
 *   3. Token revocation on logout
 * - Current implementation: token expires after duration specified by backend
 * - No refresh token flow: new login required after expiration
 */

class Auth {
    /**
     * Render login page
     */
    static renderLoginPage() {
        return `
            <div class="container d-flex align-items-center justify-content-center vh-100">
                <div class="card shadow-sm" style="width: 100%; max-width: 400px;">
                    <div class="card-body p-5">
                        <h2 class="card-title text-center mb-4">Aegis-X Login</h2>
                        
                        <form id="loginForm">
                            <div class="mb-3">
                                <label for="email" class="form-label">Email</label>
                                <input type="email" class="form-control" id="email" required>
                            </div>
                            
                            <div class="mb-3">
                                <label for="password" class="form-label">Password</label>
                                <input type="password" class="form-control" id="password" required>
                            </div>
                            
                            <button type="submit" class="btn btn-primary w-100 mb-3">Login</button>
                        </form>
                        
                        <p class="text-center text-muted mb-0">
                            Don't have an account? 
                            <a href="#register" class="text-decoration-none">Register</a>
                        </p>
                        
                        <div id="loginError" class="alert alert-danger mt-3" style="display: none;"></div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Render register page
     */
    static renderRegisterPage() {
        return `
            <div class="container d-flex align-items-center justify-content-center vh-100">
                <div class="card shadow-sm" style="width: 100%; max-width: 400px;">
                    <div class="card-body p-5">
                        <h2 class="card-title text-center mb-4">Create Account</h2>
                        
                        <form id="registerForm">
                            <div class="mb-3">
                                <label for="fullName" class="form-label">Full Name</label>
                                <input type="text" class="form-control" id="fullName" required>
                            </div>
                            
                            <div class="mb-3">
                                <label for="regEmail" class="form-label">Email</label>
                                <input type="email" class="form-control" id="regEmail" required>
                            </div>
                            
                            <div class="mb-3">
                                <label for="regPassword" class="form-label">Password</label>
                                <input type="password" class="form-control" id="regPassword" required>
                            </div>
                            
                            <button type="submit" class="btn btn-primary w-100 mb-3">Register</button>
                        </form>
                        
                        <p class="text-center text-muted mb-0">
                            Already have an account? 
                            <a href="#login" class="text-decoration-none">Login</a>
                        </p>
                        
                        <div id="registerError" class="alert alert-danger mt-3" style="display: none;"></div>
                    </div>
                </div>
            </div>
        `;
    }

    /**
     * Handle login form submission
     */
    static async handleLogin(email, password) {
        try {
            const response = await api.login(email, password);
            
            // Transform backend response: { token, userId, email, role, expiresIn, tokenType }
            // to user object: { id, email, fullName, role }
            const user = {
                id: response.userId,
                email: response.email,
                fullName: response.email.split('@')[0], // Fallback: use email prefix
                role: response.role
            };
            
            localStorage.setItem('user', JSON.stringify(user));
            window.location.hash = '#dashboard';
            
        } catch (error) {
            document.getElementById('loginError').textContent = `Login failed: ${error.message}`;
            document.getElementById('loginError').style.display = 'block';
        }
    }

    /**
     * Handle register form submission
     */
    static async handleRegister(fullName, email, password) {
        try {
            const response = await api.register(email, password, fullName);
            
            // Transform backend response: { token, userId, email, role, expiresIn, tokenType }
            // to user object: { id, email, fullName, role }
            const user = {
                id: response.userId,
                email: response.email,
                fullName: fullName,
                role: response.role
            };
            
            localStorage.setItem('user', JSON.stringify(user));
            window.location.hash = '#dashboard';
            
        } catch (error) {
            document.getElementById('registerError').textContent = `Registration failed: ${error.message}`;
            document.getElementById('registerError').style.display = 'block';
        }
    }

    /**
     * Check if user is authenticated
     */
    static isAuthenticated() {
        return !!localStorage.getItem('authToken') && !!localStorage.getItem('user');
    }

    /**
     * Get current user
     */
    static getCurrentUser() {
        const user = localStorage.getItem('user');
        return user ? JSON.parse(user) : null;
    }

    /**
     * Logout user
     */
    static logout() {
        localStorage.removeItem('authToken');
        localStorage.removeItem('user');
        api.clearAuthToken();
        window.location.hash = '#login';
    }
}
