package com.mohan.payments.merchant;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    Optional<Merchant> findByEmail(String email);
}
