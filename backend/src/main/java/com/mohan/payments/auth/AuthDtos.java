package com.mohan.payments.auth;

import java.time.Instant;
import java.util.UUID;

import com.mohan.payments.merchant.Merchant;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 128) String password) {

        /** Records print every field by default; never let the password reach a log line. */
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=****]";
        }
    }

    public record LoginResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            Instant expiresAt,
            MerchantProfile merchant) {
    }

    public record MerchantProfile(UUID id, String name, String email) {

        static MerchantProfile from(Merchant merchant) {
            return new MerchantProfile(merchant.getId(), merchant.getName(), merchant.getEmail());
        }
    }
}
