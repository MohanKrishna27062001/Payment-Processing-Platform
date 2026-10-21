package com.mohan.payments.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.mohan.payments.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes create/capture/refund safe to retry.
 * <p>
 * The key record, the business operation and the stored response are written in <b>one transaction</b>:
 * <ul>
 *   <li>First request with a key: insert the key, run the operation, store its response, commit.</li>
 *   <li>Retry with the same key and same request: return the stored response (header {@code Idempotent-Replayed}).</li>
 *   <li>Same key, different request: 422, because the client has a bug.</li>
 *   <li>Two identical requests at the same moment: the unique constraint on (merchant, key) makes the second insert
 *       wait for the first transaction; once it commits, the second gets a constraint violation and replays the
 *       committed response. Exactly one operation runs.</li>
 *   <li>Operation fails or the process crashes: everything rolls back, including the key, so a retry runs fresh.</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private final IdempotencyRepository repository;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Duration ttl;

    public IdempotencyService(IdempotencyRepository repository, PlatformTransactionManager transactionManager,
            JsonMapper jsonMapper, Clock clock, @Value("${app.idempotency.ttl:PT24H}") Duration ttl) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * @param operation   identifies the endpoint, e.g. {@code "POST /api/v1/payments/{id}/capture"}
     * @param requestBody the request payload (or null); part of the fingerprint
     */
    public ResponseEntity<?> execute(UUID merchantId, String key, String operation, Object requestBody,
            Supplier<ResponseEntity<?>> action) {
        if (!VALID_KEY.matcher(key).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Invalid Idempotency-Key",
                    "Idempotency-Key must be 1-255 printable ASCII characters (a UUID is recommended).");
        }
        String requestHash = fingerprint(operation, requestBody);

        Optional<IdempotencyRecord> existing = repository.findByMerchantIdAndIdempotencyKey(merchantId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }
        try {
            return transaction.execute(status -> {
                Instant now = clock.instant();
                IdempotencyRecord record = repository.saveAndFlush(
                        IdempotencyRecord.start(merchantId, key, requestHash, now, now.plus(ttl)));
                ResponseEntity<?> response = action.get();
                record.complete(response.getStatusCode().value(), jsonMapper.writeValueAsString(response.getBody()));
                return response;
            });
        } catch (DataIntegrityViolationException e) {
            // Lost the race to a concurrent request with the same key; it has committed, so replay its result.
            return repository.findByMerchantIdAndIdempotencyKey(merchantId, key)
                    .map(record -> replay(record, requestHash))
                    .orElseThrow(() -> e);
        }
    }

    private ResponseEntity<?> replay(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key reused",
                    "This Idempotency-Key was already used for a different request. Use a new key per operation.");
        }
        if (record.getStatus() != IdempotencyRecord.Status.COMPLETED) {
            throw ApiException.conflict("IDEMPOTENCY_REQUEST_IN_PROGRESS", "Request in progress",
                    "A request with this Idempotency-Key is still being processed. Retry shortly.");
        }
        log.info("Replaying stored response for idempotent request");
        return ResponseEntity.status(record.getResponseStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAYED_HEADER, "true")
                .body(record.getResponseBody());
    }

    private String fingerprint(String operation, Object requestBody) {
        String canonical = operation + "\n" + (requestBody == null ? "" : jsonMapper.writeValueAsString(requestBody));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Scheduled(fixedDelayString = "${app.idempotency.cleanup-interval:PT1H}", initialDelayString = "PT1M")
    public void purgeExpired() {
        int deleted = repository.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("Purged {} expired idempotency keys", deleted);
        }
    }
}
