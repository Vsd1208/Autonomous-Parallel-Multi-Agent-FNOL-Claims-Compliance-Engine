package com.guidewire.fnol.api.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.BranchBResult;
import com.guidewire.fnol.common.Models.FNOLResponse;
import com.guidewire.fnol.common.Models.PIIResult;
import com.guidewire.fnol.common.Models.PiiDetection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoredPrivacyResultTest {

    @Test
    @DisplayName("The stored analysis drops the text but keeps what was found and the re-scan's result")
    void keepsScanResultWithoutText() {
        PIIResult pii = new PIIResult("masked text", List.of("PHONE"), true, null,
                List.of(new PiiDetection("PHONE", "PATTERN", 1, null, true)), PIIResult.VALIDATED);
        FNOLResponse stored = ClaimEntity.withoutPersonalText(new FNOLResponse("C-1", "OPEN", null, new BranchBResult(pii, null, null, List.of()),
                null, null, null, null, null, null, null, null, null, null));
        assertThat(stored.branchB().pii().redactedDescription()).isNull();
        assertThat(stored.branchB().pii().validation()).isEqualTo(PIIResult.VALIDATED);
        assertThat(stored.branchB().pii().detections()).hasSize(1);
    }

    @Test
    @DisplayName("A record stored before this change still reads, with its old number and no scan result")
    void oldRecordsStillRead() throws Exception {
        PIIResult old = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue(
                "{\"detectedTypes\":[],\"piiDetected\":false,\"redactionConfidence\":0.95}", PIIResult.class);
        assertThat(old.redactionConfidence()).isEqualByComparingTo("0.95");
        assertThat(old.validation()).isNull();
    }
}
