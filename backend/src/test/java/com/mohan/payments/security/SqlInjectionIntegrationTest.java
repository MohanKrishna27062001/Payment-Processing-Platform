package com.mohan.payments.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.mohan.payments.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Fires classic injection payloads at every input a caller controls and asserts they arrive as <b>data</b>.
 * <p>
 * The protection is structural rather than defensive: every query is a derived method, a {@code @Query} with
 * named parameters, or built through the Criteria API, so a value has no path to become SQL. These tests exist
 * because that property is easy to lose later -- one concatenated string in a new repository method would do
 * it, and nothing else in the build would notice.
 * <p>
 * {@code ORDER BY} is the exception that needs code: a column name is not a value, so it cannot be bound to a
 * placeholder. {@link com.mohan.payments.payment.PaymentSort} resolves the caller's string against an
 * allowlist, so what reaches the query is always a constant from our own source.
 */
class SqlInjectionIntegrationTest extends IntegrationTestBase {

    private static final List<String> PAYLOADS = List.of(
            "' OR '1'='1",
            "'; DROP TABLE payments; --",
            "' UNION SELECT NULL,NULL,NULL --",
            "1' OR 1=1 --",
            "admin'--",
            "' OR ''='",
            "') OR ('a'='a");

    private String acme;

    @BeforeEach
    void loginAsAcme() throws Exception {
        acme = login(ACME_EMAIL);
    }

    @Test
    void searchPayloadsMatchNothingAndChangeNothing() throws Exception {
        authorizedPayment(acme, 2500);
        long before = countPayments();

        for (String payload : PAYLOADS) {
            mvc.perform(get("/api/v1/payments").param("q", payload)
                            .header("Authorization", "Bearer " + acme))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(0));
        }

        // Nothing was dropped, deleted or revealed: the payloads were only ever text to match against.
        assertThat(countPayments()).isEqualTo(before);
        assertThat(tableExists("payments")).isTrue();
        assertThat(tableExists("payment_events")).isTrue();
    }

    @Test
    void aPayloadStoredAsAReferenceComesBackVerbatim() throws Exception {
        String payload = "' OR '1'='1";
        createPayment(acme, UUID.randomUUID().toString(), 2500, "tok_visa", payload)
                .andExpect(status().isCreated());

        // Round-tripping through the database unchanged is the clearest evidence it is treated as a value.
        mvc.perform(get("/api/v1/payments").param("q", payload)
                        .header("Authorization", "Bearer " + acme))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].merchantReference").value(payload));
    }

    @Test
    void sortOnlyAcceptsAllowlistedColumns() throws Exception {
        for (String payload : List.of("id; DROP TABLE payments", "(select 1)", "password", "merchantId")) {
            mvc.perform(get("/api/v1/payments").param("sort", payload)
                            .header("Authorization", "Bearer " + acme))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SORT_FIELD"));
        }
    }

    @Test
    void sortDirectionIsNotAnOpening() throws Exception {
        mvc.perform(get("/api/v1/payments").param("sort", "amount,desc; DROP TABLE payments")
                        .header("Authorization", "Bearer " + acme))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT_DIRECTION"));
    }

    @Test
    void filterParametersRejectInjectionBeforeReachingTheQuery() throws Exception {
        mvc.perform(get("/api/v1/payments").param("currency", "USD' OR '1'='1")
                        .header("Authorization", "Bearer " + acme))
                .andExpect(status().is4xxClientError());

        mvc.perform(get("/api/v1/payments").param("minAmount", "1 OR 1=1")
                        .header("Authorization", "Bearer " + acme))
                .andExpect(status().is4xxClientError());

        mvc.perform(get("/api/v1/payments").param("status", "CAPTURED'; DROP TABLE payments; --")
                        .header("Authorization", "Bearer " + acme))
                .andExpect(status().is4xxClientError());

        assertThat(tableExists("payments")).isTrue();
    }

    @Test
    void loginIsNotInjectable() throws Exception {
        for (String payload : List.of("admin'--", "' OR '1'='1")) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\",\"password\":\"anything\"}".formatted(payload)))
                    // Rejected as a bad email or bad credentials -- never a 500, and never a session.
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.accessToken").doesNotExist());
        }
    }

    private long countPayments() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM payments", Long.class);
        return count == null ? 0 : count;
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = ?)",
                Boolean.class, table));
    }
}
