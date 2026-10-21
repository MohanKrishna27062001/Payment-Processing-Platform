package com.mohan.payments.auth;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.mohan.payments.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

class AuthIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void loginReturnsBearerTokenAndProfile() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ACME@test.local\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.accessToken").value(notNullValue()))
                .andExpect(jsonPath("$.merchant.id").value(ACME_ID.toString()))
                .andExpect(jsonPath("$.merchant.name").value("Argyle Payments"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGetIdenticalResponses() throws Exception {
        for (String body : new String[] {
                "{\"email\":\"acme@test.local\",\"password\":\"wrong\"}",
                "{\"email\":\"nobody@test.local\",\"password\":\"wrong\"}"}) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.detail").value("Invalid email or password."));
        }
    }

    @Test
    void malformedLoginIsAValidationError() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("password")));
    }

    @Test
    void meReturnsTheAuthenticatedMerchant() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + login(GLOBEX_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(GLOBEX_ID.toString()))
                .andExpect(jsonPath("$.email").value(GLOBEX_EMAIL));
    }

    @Test
    void missingTokenIsRejectedWithProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/payments").header("X-Request-Id", "req-missing-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("X-Request-Id", "req-missing-token"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").value("req-missing-token"));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = login(ACME_EMAIL);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        mvc.perform(get("/api/v1/payments").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        Instant issued = Instant.now().minusSeconds(3600);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("payments-api").subject(ACME_ID.toString())
                .issuedAt(issued).expiresAt(issued.plusSeconds(900)).build();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                claims)).getTokenValue();

        mvc.perform(get("/api/v1/payments").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void localDashboardOnAnyPortPassesCorsButForeignSitesDoNot() throws Exception {
        String body = "{\"email\":\"acme@test.local\",\"password\":\"" + PASSWORD + "\"}";

        mvc.perform(post("/api/v1/auth/login").header("Origin", "http://localhost:5174")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5174"));

        mvc.perform(post("/api/v1/auth/login").header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthProbesArePublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
