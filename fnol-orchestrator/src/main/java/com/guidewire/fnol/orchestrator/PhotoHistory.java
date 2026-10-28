package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.FNOLPayload;

import java.util.List;

/**
 * Earlier claims that already contain one of this intake's photos (the same file, or the same picture resized or
 * recompressed). Implemented where the stored evidence lives (fnol-api); a match raises PHOTO_REUSED.
 */
public interface PhotoHistory {

    /** Claim ids, without repeats; empty when no photo was seen before. Must not throw. */
    List<String> claimsWithSamePhotos(FNOLPayload payload);

    PhotoHistory NONE = payload -> List.of();
}
