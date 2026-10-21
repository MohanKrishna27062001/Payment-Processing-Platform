package com.mohan.payments.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.mohan.payments.common.observability.TraceContextSupport;
import com.mohan.payments.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxJobRepository jobs;
    private final OutboxProperties properties;
    private final TraceContextSupport tracing;
    private final Clock clock;

    public OutboxService(OutboxJobRepository jobs, OutboxProperties properties, TraceContextSupport tracing,
            Clock clock) {
        this.jobs = jobs;
        this.properties = properties;
        this.tracing = tracing;
        this.clock = clock;
    }

    /** Must join the caller's transaction: the job only exists if the capture that needs it commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueSettlement(UUID paymentId) {
        Instant now = clock.instant();
        OutboxJob job = jobs.save(OutboxJob.create(JobType.SETTLE_PAYMENT, paymentId,
                now.plus(properties.settlementDelay()), CorrelationIdFilter.current(),
                tracing.currentTraceparent(), now));
        log.atInfo().addKeyValue("jobId", job.getId()).addKeyValue("jobType", job.getType())
                .addKeyValue("runAt", job.getNextAttemptAt()).log("Enqueued settlement job");
    }
}
