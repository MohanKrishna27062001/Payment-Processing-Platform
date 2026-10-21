package com.mohan.payments.payment.processor;

import java.net.http.HttpClient;
import java.util.Set;
import java.util.function.Supplier;

import com.mohan.payments.payment.processor.ProcessorDtos.AmountRequest;
import com.mohan.payments.payment.processor.ProcessorDtos.AuthorizeRequest;
import com.mohan.payments.payment.processor.ProcessorDtos.AuthorizeResponse;
import com.mohan.payments.payment.processor.ProcessorDtos.Card;
import com.mohan.payments.payment.processor.ProcessorDtos.OperationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to the payment processor over HTTP. Point {@code app.processor.base-url} at the WireMock stub for local
 * runs and tests, or at the real processor in production; nothing else in the application changes.
 * <p>
 * How responses are interpreted, in the order the checks run:
 * <ul>
 *   <li><b>2xx</b> - the operation happened. For authorize, {@code approved} in the body decides whether this is
 *       an approval or a decline; a decline is a business outcome, not an error.</li>
 *   <li><b>409 with {@code already_captured} / {@code already_refunded} / {@code already_settled}</b> - the
 *       processor is telling us the state we wanted already holds, which almost always means a previous attempt
 *       succeeded and its response was lost. Treated as success, so a retry cannot move money twice.</li>
 *   <li><b>other 4xx</b> - a permanent refusal: {@link ProcessorRejectedException}. Retrying will not help.</li>
 *   <li><b>5xx, timeouts, connection resets</b> - the outcome is unknown: {@link ProcessorUnavailableException},
 *       which rolls the transaction back (idempotency key included) so the caller can safely retry.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.processor.mode", havingValue = "http")
public class HttpPaymentProcessorClient implements PaymentProcessorClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentProcessorClient.class);
    private static final String AUTHORIZATIONS = "/processor/v1/authorizations";

    /** Processor answers meaning "this already happened"; the desired state holds, so the call succeeded. */
    private static final Set<String> ALREADY_DONE =
            Set.of("already_captured", "already_refunded", "already_settled");

    private final RestClient http;

    public HttpPaymentProcessorClient(ProcessorProperties properties) {
        // HTTP/1.1 explicitly: the JDK client defaults to HTTP/2 and tries the cleartext h2c upgrade,
        // which the stub's server refuses with RST_STREAM. Real processor APIs are HTTP/1.1 anyway.
        HttpClient jdkClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
        // Deliberately short: this call runs inside a database transaction holding a pooled connection.
        factory.setReadTimeout(properties.readTimeout());
        this.http = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
        log.info("Payment processor: HTTP at {} (connect {}, read {})", properties.baseUrl(),
                properties.connectTimeout(), properties.readTimeout());
    }

    @Override
    public AuthorizationResult authorize(String paymentMethodToken, long amountMinor, String currency) {
        ResponseEntity<AuthorizeResponse> response = send("authorize", () -> http.post()
                .uri(AUTHORIZATIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AuthorizeRequest(paymentMethodToken, amountMinor, currency))
                .retrieve()
                .onStatus(status -> true, (request, res) -> { })
                .toEntity(AuthorizeResponse.class));

        AuthorizeResponse body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw failure("authorize", null, response.getStatusCode().value(), body == null ? null : body.error());
        }
        if (body == null) {
            throw new ProcessorUnavailableException("Processor returned an empty body for authorize");
        }
        if (!body.approved()) {
            return AuthorizationResult.declined(body.declineReason());
        }
        Card card = body.card();
        return AuthorizationResult.approved(body.processorReference(),
                card == null ? null : card.brand(), card == null ? null : card.last4());
    }

    @Override
    public void capture(String processorReference, long amountMinor) {
        operation("capture", processorReference, amountMinor);
    }

    @Override
    public void refund(String processorReference, long amountMinor) {
        operation("refund", processorReference, amountMinor);
    }

    @Override
    public void settle(String processorReference, long amountMinor) {
        operation("settle", processorReference, amountMinor);
    }

    private void operation(String operation, String processorReference, long amountMinor) {
        ResponseEntity<OperationResponse> response = send(operation, () -> http.post()
                .uri(AUTHORIZATIONS + "/{reference}/" + operation, processorReference)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AmountRequest(amountMinor))
                .retrieve()
                .onStatus(status -> true, (request, res) -> { })
                .toEntity(OperationResponse.class));

        if (response.getStatusCode().is2xxSuccessful()) {
            return;
        }
        OperationResponse body = response.getBody();
        String error = body == null ? null : body.error();
        if (ALREADY_DONE.contains(error)) {
            log.atInfo().addKeyValue("processorReference", processorReference).addKeyValue("error", error)
                    .log("Processor reports the {} already happened; treating as success", operation);
            return;
        }
        throw failure(operation, processorReference, response.getStatusCode().value(), error);
    }

    private RuntimeException failure(String operation, String processorReference, int status, String error) {
        String detail = "Processor " + operation + " returned " + status
                + (error == null ? "" : " (" + error + ")")
                + (processorReference == null ? "" : " for " + processorReference);
        if (status >= 500) {
            return new ProcessorUnavailableException(detail);
        }
        return new ProcessorRejectedException(error == null ? "processor_rejected" : error, detail);
    }

    /** Any transport-level problem means the outcome is unknown, which is a retryable condition. */
    private <T> ResponseEntity<T> send(String operation, Supplier<ResponseEntity<T>> call) {
        try {
            return call.get();
        } catch (RestClientException e) {
            throw new ProcessorUnavailableException(
                    "Processor " + operation + " did not complete: " + e.getMessage());
        }
    }
}
