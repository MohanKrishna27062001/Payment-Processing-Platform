package com.mohan.payments.common.observability;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carries a trace across the async boundary. When an API request enqueues a job, the current W3C
 * {@code traceparent} is saved on the job row; the worker continues that trace, so the API request and the
 * background processing show up as one trace (same traceId in logs and in a tracing backend).
 */
@Component
public class TraceContextSupport {

    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceContextSupport(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    /** The current trace context as a W3C traceparent header value, or null when no trace is active. */
    public String currentTraceparent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /** Runs work in a new span that is a child of {@code traceparent} (or a new trace if absent). */
    public <T> T inSpan(String traceparent, String spanName, Supplier<T> work) {
        Span span = traceparent == null
                ? tracer.nextSpan().name(spanName).start()
                : propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get).name(spanName).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
