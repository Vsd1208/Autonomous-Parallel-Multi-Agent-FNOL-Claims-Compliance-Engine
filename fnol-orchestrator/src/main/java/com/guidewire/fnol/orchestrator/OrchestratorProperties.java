package com.guidewire.fnol.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code fnol.orchestrator.*}: overall fan-out deadline and the bounded executor shared by all requests.
 * Each intake uses two threads (one per branch), so pool-size 8 runs four intakes at once and
 * queue-size 32 holds the branches of sixteen more before new intakes are refused with a 504.
 */
@Component
@ConfigurationProperties(prefix = "fnol.orchestrator")
public class OrchestratorProperties {

    private long timeoutSeconds = 10;
    private int poolSize = 8;
    private int queueSize = 32;

    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int poolSize) { this.poolSize = poolSize; }

    public int getQueueSize() { return queueSize; }
    public void setQueueSize(int queueSize) { this.queueSize = queueSize; }
}
