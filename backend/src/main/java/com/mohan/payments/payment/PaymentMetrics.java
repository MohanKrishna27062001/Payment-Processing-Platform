package com.mohan.payments.payment;

import java.util.List;

import com.mohan.payments.payment.domain.PaymentRepository;
import com.mohan.payments.payment.domain.PaymentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Business metrics:
 * <ul>
 *   <li>{@code payments.transitions{from,to}}: every lifecycle change, so created/captured/refunded/failed/settled
 *       rates can all be graphed from one counter.</li>
 *   <li>{@code payments.current{status}}: how many payments are in each state right now (refreshed periodically).</li>
 * </ul>
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;
    private final PaymentRepository payments;
    private final MultiGauge currentByStatus;

    public PaymentMetrics(MeterRegistry registry, PaymentRepository payments) {
        this.registry = registry;
        this.payments = payments;
        this.currentByStatus = MultiGauge.builder("payments.current")
                .description("Payments currently in each lifecycle state")
                .register(registry);
    }

    public void recordTransition(PaymentStatus from, PaymentStatus to) {
        Counter.builder("payments.transitions")
                .description("Payment lifecycle transitions")
                .tag("from", from == null ? "NONE" : from.name())
                .tag("to", to.name())
                .register(registry)
                .increment();
    }

    @Scheduled(fixedDelayString = "${app.metrics.refresh-interval:PT30S}", initialDelayString = "PT5S")
    public void refreshStateGauges() {
        List<MultiGauge.Row<?>> rows = payments.countByStatus().stream()
                .<MultiGauge.Row<?>>map(c -> MultiGauge.Row.of(Tags.of("status", c.getStatus().name()), c.getTotal()))
                .toList();
        currentByStatus.register(rows, true);
    }
}
