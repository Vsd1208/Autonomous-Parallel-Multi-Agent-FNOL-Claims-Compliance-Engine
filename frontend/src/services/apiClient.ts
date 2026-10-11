const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

export const apiClient = {
  // Submit FNOL intake form
  submitFNOL: async (payload: {
    policyNumber: string;
    incidentDate: string;
    state: string;
    description: string;
    claimantName: string;
    claimantDob: string;
    claimantSsn: string;
    evidenceUris: string[];
  }) => {
    const response = await fetch(`${API_BASE_URL}/api/v1/fnol/intake`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.message || `HTTP ${response.status}`);
    }

    return response.json();
  },

  // Get claim status by ID
  getClaimStatus: async (claimId: string) => {
    const response = await fetch(`${API_BASE_URL}/api/v1/fnol/status/${claimId}`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
      },
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.message || `HTTP ${response.status}`);
    }

    return response.json();
  },

  // Check API health
  healthCheck: async () => {
    const response = await fetch(`${API_BASE_URL}/api/v1/health`, {
      method: 'GET',
    });

    if (!response.ok) {
      throw new Error(`API health check failed: ${response.status}`);
    }

    return response.json();
  },

  // Get policy details
  getPolicyDetails: async (policyNumber: string) => {
    const response = await fetch(`${API_BASE_URL}/api/v1/policies/${policyNumber}`, {
      method: 'GET',
    });

    if (!response.ok) {
      throw new Error(`Failed to fetch policy: ${response.status}`);
    }

    return response.json();
  },
};