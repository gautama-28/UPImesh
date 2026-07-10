const BASE_URL = 'http://localhost:8080/api';

async function request(path, options = {}) {
    const response = await fetch(`${BASE_URL}${path}`, {
        headers: { 'Content-Type': 'application/json' },
        ...options,
    });

    if (!response.ok) {
        throw new Error(`API error ${response.status}: ${response.statusText}`);
    }

    return response.json();
}

export const api = {
    getAccounts: () => request('/accounts'),
    getTransactions: () => request('/transactions'),
    getMeshState: () => request('/mesh/state'),
    getServerKey: () => request('/server-key'),

    sendPayment: (payload) =>
        request('/demo/send', {
            method: 'POST',
            body: JSON.stringify(payload),
        }),

    runGossip: () => request('/mesh/gossip', { method: 'POST' }),
    flushBridges: () => request('/mesh/flush', { method: 'POST' }),
    resetMesh: () => request('/mesh/reset', { method: 'POST' }),

    getInsightContext: (vpa, days = 7) =>
        request(`/insights/context?vpa=${vpa}&days=${days}`),

    askInsight: (vpa, question, days = 7) =>
        request('/insights/ask', {
            method: 'POST',
            body: JSON.stringify({ vpa, question, days }),
        }),
};

