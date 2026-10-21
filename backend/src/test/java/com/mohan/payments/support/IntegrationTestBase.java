package com.mohan.payments.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Full application context against a real PostgreSQL, exercised over HTTP through MockMvc. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
public abstract class IntegrationTestBase {

    protected static final UUID ACME_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    protected static final UUID GLOBEX_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    protected static final String ACME_EMAIL = "acme@test.local";
    protected static final String GLOBEX_EMAIL = "globex@test.local";
    protected static final String PASSWORD = "integration-test-password";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @BeforeAll
    static void requireDatabase() {
        Assumptions.assumeTrue(TestDatabase.available(),
                "No PostgreSQL available (neither Docker nor a local instance); skipping integration tests");
    }

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE outbox_jobs, payment_events, idempotency_keys, payments, merchants RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO merchants (id, name, email, password_hash) VALUES (?, ?, ?, ?), (?, ?, ?, ?)",
                ACME_ID, "Argyle Payments", ACME_EMAIL, PASSWORD_HASH,
                GLOBEX_ID, "Globex Electronics", GLOBEX_EMAIL, PASSWORD_HASH);
    }

    protected String login(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    protected ResultActions createPayment(String token, String idempotencyKey, long amount, String cardToken,
            String reference) throws Exception {
        String json = """
                {"amount": %d, "currency": "USD", "merchantReference": "%s", "paymentMethod": {"token": "%s"}}
                """.formatted(amount, reference, cardToken);
        return mvc.perform(post("/api/v1/payments").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Creates an authorized payment and returns its id. */
    protected String authorizedPayment(String token, long amount) throws Exception {
        String body = createPayment(token, UUID.randomUUID().toString(), amount, "tok_visa", "order-" + amount)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    protected ResultActions capture(String token, String paymentId, String idempotencyKey) throws Exception {
        return mvc.perform(post("/api/v1/payments/{id}/capture", paymentId)
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", idempotencyKey));
    }

    protected ResultActions refund(String token, String paymentId, String idempotencyKey) throws Exception {
        return mvc.perform(post("/api/v1/payments/{id}/refund", paymentId)
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", idempotencyKey));
    }

    protected ResultActions getPayment(String token, String paymentId) throws Exception {
        return mvc.perform(get("/api/v1/payments/{id}", paymentId).header("Authorization", "Bearer " + token));
    }

    protected String paymentStatus(String paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?::uuid", String.class, paymentId);
    }
}
