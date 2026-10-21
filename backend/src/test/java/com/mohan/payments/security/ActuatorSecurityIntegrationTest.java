package com.mohan.payments.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.mohan.payments.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * The operator endpoints show and requeue every merchant's jobs, so a merchant token, however valid, must not
 * open them. Only the operator account (HTTP Basic) does; probes and the Prometheus scrape stay public.
 */
class ActuatorSecurityIntegrationTest extends IntegrationTestBase {

    private static final String OPERATOR = basic("ops", "test-operator-password");

    @Test
    void merchantTokenCannotReadOtherMerchantsDeadLetters() throws Exception {
        mvc.perform(get("/actuator/outbox").header("Authorization", "Bearer " + login(ACME_EMAIL)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"operator\""))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void merchantTokenCannotRequeueJobs() throws Exception {
        mvc.perform(post("/actuator/outbox/1").header("Authorization", "Bearer " + login(ACME_EMAIL)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void merchantTokenCannotReadMetricsEndpoint() throws Exception {
        mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + login(GLOBEX_EMAIL)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousAndWrongPasswordAreRejected() throws Exception {
        mvc.perform(get("/actuator/outbox")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/outbox").header("Authorization", basic("ops", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void operatorCanReadAndRequeue() throws Exception {
        mvc.perform(get("/actuator/outbox").header("Authorization", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadLetters").isArray());
        // No such job, but getting a business answer proves the request was let through.
        mvc.perform(post("/actuator/outbox/999999").header("Authorization", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    void operatorCredentialsDoNotWorkOnTheMerchantApi() throws Exception {
        mvc.perform(get("/api/v1/payments").header("Authorization", OPERATOR))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void probesStayPublic() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
