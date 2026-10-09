
package com.guidewire.fnol.api;

import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IdempotencyStore {

    private final Set<String> processedClaims =
            ConcurrentHashMap.newKeySet();

    public boolean tryAcquire(String policyNumber,
                              String incidentDate,
                              String state) {
        String key = createKey(policyNumber, incidentDate, state);
        return processedClaims.add(key);
    }

    public void release(String policyNumber,
                        String incidentDate,
                        String state) {
        String key = createKey(policyNumber, incidentDate, state);
        processedClaims.remove(key);
    }

    private String createKey(String policyNumber,
                             String incidentDate,
                             String state) {
        return policyNumber + "|" + incidentDate + "|" + state;
    }
}