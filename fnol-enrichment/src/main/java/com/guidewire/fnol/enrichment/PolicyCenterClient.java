package com.guidewire.fnol.enrichment;

import com.guidewire.fnol.common.Models.*;

import java.util.List;

public interface PolicyCenterClient {

    Policy getPolicy(String policyNumber);

    List<Coverage> getCoverages(String policyNumber);

    /** Prior claims on the policy (GET /pc/policies/{policyNumber}/history). */
    List<ClaimHistory> getPolicyClaimHistory(String policyNumber);
}
