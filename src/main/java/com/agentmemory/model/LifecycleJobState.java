package com.agentmemory.model;

import java.time.Instant;

public class LifecycleJobState {

    private String jobName;
    private String leaseOwner;
    private Instant leaseUntil;
    private Instant lastStartedAt;
    private Instant lastCompletedAt;
    private String lastError;

    public LifecycleJobState() {}

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }

    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }

    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }

    public Instant getLastStartedAt() { return lastStartedAt; }
    public void setLastStartedAt(Instant lastStartedAt) { this.lastStartedAt = lastStartedAt; }

    public Instant getLastCompletedAt() { return lastCompletedAt; }
    public void setLastCompletedAt(Instant lastCompletedAt) { this.lastCompletedAt = lastCompletedAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
