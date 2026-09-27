/**
 * Aegis-X API Client Layer
 * Communicates with the Spring Boot backend (/api/...) and handles token injection
 */

const API_BASE_URL = window.AEGIS_API_BASE_URL || 'http://localhost:8080/api';
let correlationSequence = 0;

function createCorrelationId() {
  return globalThis.crypto?.randomUUID?.() || `aegis-${Date.now()}-${++correlationSequence}`;
}

function resolveUrl(endpoint) {
  if (endpoint.startsWith('/actuator/') || endpoint === '/prometheus') {
    return `${API_BASE_URL.replace(/\/api\/?$/, '')}${endpoint}`;
  }
  return `${API_BASE_URL}${endpoint}`;
}

async function readResponse(response) {
  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }

  if (!response.ok) {
    const message = body && typeof body === 'object'
      ? body.message || body.error || `HTTP error ${response.status}`
      : body || `HTTP error ${response.status}`;
    throw new Error(message);
  }

  if (body && typeof body === 'object' && body.success === false) {
    throw new Error(body.message || body.error || 'The API rejected the request');
  }

  if (body && typeof body === 'object' && body.success === true && Object.hasOwn(body, 'data')) {
    return body.data;
  }
  return body;
}

export const API = {
  getAuthToken() {
    return sessionStorage.getItem('aegis_jwt_token');
  },

  setAuthToken(token) {
    if (token) {
      sessionStorage.setItem('aegis_jwt_token', token);
    } else {
      sessionStorage.removeItem('aegis_jwt_token');
    }
    localStorage.removeItem('aegis_jwt_token');
  },

  async request(endpoint, options = {}) {
    const token = this.getAuthToken();
    const headers = {
      ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
      'X-Correlation-ID': createCorrelationId(),
      ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
      ...(options.headers || {})
    };

    const response = await fetch(resolveUrl(endpoint), {
      ...options,
      headers
    });
    return readResponse(response);
  },

  async uploadDocument(transactionId, file, documentType = 'COMMERCIAL_INVOICE', onProgress = () => {}) {
    const token = this.getAuthToken();
    const formData = new FormData();
    formData.append('file', file);
    formData.append('documentType', documentType);

    return new Promise((resolve, reject) => {
      const request = new XMLHttpRequest();
      request.open('POST', resolveUrl(`/transactions/${transactionId}/documents`));
      request.setRequestHeader('X-Correlation-ID', createCorrelationId());
      if (token) request.setRequestHeader('Authorization', `Bearer ${token}`);
      request.upload.addEventListener('progress', (event) => {
        if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100));
      });
      request.addEventListener('load', async () => {
        try {
          resolve(await readResponse({
            ok: request.status >= 200 && request.status < 300,
            status: request.status,
            text: async () => request.responseText
          }));
        } catch (error) {
          reject(error);
        }
      });
      request.addEventListener('error', () => reject(new Error('Upload network request failed')));
      request.addEventListener('abort', () => reject(new Error('Upload was cancelled')));
      request.send(formData);
    });
  }
};
