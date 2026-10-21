package com.mohan.payments.auth;

import com.mohan.payments.auth.AuthDtos.LoginRequest;
import com.mohan.payments.auth.AuthDtos.LoginResponse;
import com.mohan.payments.auth.AuthDtos.MerchantProfile;
import com.mohan.payments.security.CurrentMerchant;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public MerchantProfile me(@AuthenticationPrincipal Jwt jwt) {
        return authService.profile(CurrentMerchant.id(jwt));
    }
}
