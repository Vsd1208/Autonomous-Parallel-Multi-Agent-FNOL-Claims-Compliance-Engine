package com.guidewire.fnol.enrichment;
import com.guidewire.fnol.common.Models.*;import java.util.*;
public interface PolicyCenterClient { Policy getPolicy(String policyNumber); List<Coverage> getCoverages(String policyNumber); List<ClaimHistory> getPolicyHistory(String policyNumber);

    List<ClaimHistory> getPolicyClaimHistory(String policyNumber);
}
