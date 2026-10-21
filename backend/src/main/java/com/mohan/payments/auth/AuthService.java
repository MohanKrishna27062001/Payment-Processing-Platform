package com.mohan.payments.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.mohan.payments.auth.AuthDtos.LoginRequest;
import com.mohan.payments.auth.AuthDtos.LoginResponse;
import com.mohan.payments.auth.AuthDtos.MerchantProfile;
import com.mohan.payments.common.error.ApiException;
import com.mohan.payments.merchant.Merchant;
import com.mohan.payments.merchant.MerchantRepository;
import com.mohan.payments.security.JwtTokenService;
import com.mohan.payments.security.JwtTokenService.IssuedToken;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final MerchantRepository merchants;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final Counter loginSuccess;
    private final Counter loginFailure;
    // Compared against when the email is unknown, so both failure paths take the same time
    // and response timing cannot be used to discover which emails have accounts.
    private final String dummyHash;

    public AuthService(MerchantRepository merchants, PasswordEncoder passwordEncoder, JwtTokenService tokens,
            MeterRegistry meterRegistry) {
        this.merchants = merchants;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.loginSuccess = Counter.builder("auth.logins").tag("result", "success")
                .description("Merchant login attempts").register(meterRegistry);
        this.loginFailure = Counter.builder("auth.logins").tag("result", "failure")
                .description("Merchant login attempts").register(meterRegistry);
        this.dummyHash = passwordEncoder.encode("timing-equalizer");
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Optional<Merchant> merchant = merchants.findByEmail(email);
        boolean passwordMatches = passwordEncoder.matches(request.password(),
                merchant.map(Merchant::getPasswordHash).orElse(dummyHash));

        if (merchant.isEmpty() || !passwordMatches) {
            loginFailure.increment();
            log.atWarn().addKeyValue("reason", merchant.isEmpty() ? "unknown_email" : "wrong_password")
                    .log("Login failed");
            // Same response for both cases: don't reveal whether the email exists.
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password.");
        }

        IssuedToken token = tokens.issue(merchant.get().getId(), merchant.get().getName());
        loginSuccess.increment();
        log.atInfo().addKeyValue("merchantId", merchant.get().getId()).log("Merchant logged in");
        return new LoginResponse(token.value(), "Bearer", token.expiresInSeconds(), token.expiresAt(),
                MerchantProfile.from(merchant.get()));
    }

    @Transactional(readOnly = true)
    public MerchantProfile profile(UUID merchantId) {
        return merchants.findById(merchantId)
                .map(MerchantProfile::from)
                .orElseThrow(() -> ApiException.unauthorized("UNKNOWN_MERCHANT", "Merchant no longer exists."));
    }
}
