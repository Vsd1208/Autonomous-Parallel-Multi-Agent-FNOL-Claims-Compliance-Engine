# Guidewire Integration

This repository does not connect to live Guidewire PolicyCenter or ClaimCenter.

The `fnol-mock-guidewire` module provides local HTTP services named Mock PolicyCenter and Mock ClaimCenter. They simulate the integration contracts required by this prototype with synthetic policies, coverages, history, and claim creation.

The replacement boundary is:

- `PolicyCenterClient`
- `ClaimCenterClient`

Current implementations:

- `MockPolicyCenterClient`
- `MockClaimCenterClient`

A production implementation, such as `GuidewirePolicyCenterClient`, should be added behind the same interfaces and selected by Spring profile/configuration. Actual endpoints, authentication, and schemas must be validated against official Guidewire documentation and customer environment details before implementation.
