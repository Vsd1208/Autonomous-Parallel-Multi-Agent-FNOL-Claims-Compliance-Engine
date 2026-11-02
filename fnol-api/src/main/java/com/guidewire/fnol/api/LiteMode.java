package com.guidewire.fnol.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The quick-review run mode, with no vision-ml service: mock vision and no second opinion. Photo blurring lives in that service,
 * so lite mode refuses to start with Gemini enabled. That way it can never send a photo to an external model unblurred.
 */
@Component
class LiteMode {

    private final boolean enabled;

    LiteMode(@Value("${fnol.lite-mode:false}") boolean enabled,
             @Value("${gemini.vision.enabled:true}") boolean geminiEnabled) {
        if (enabled && geminiEnabled) {
            throw new IllegalStateException("fnol.lite-mode needs gemini.vision.enabled=false: photo blurring is not available "
                    + "in lite mode, so no photo may go to an external model");
        }
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }

    /** For the health endpoint. */
    String secondOpinion() {
        return enabled ? "NOT_AVAILABLE_IN_LITE_MODE" : "CONFIGURED";
    }
}
