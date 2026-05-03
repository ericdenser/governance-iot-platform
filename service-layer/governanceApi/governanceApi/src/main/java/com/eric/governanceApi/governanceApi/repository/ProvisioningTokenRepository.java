package com.eric.governanceApi.governanceApi.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.eric.governanceApi.governanceApi.model.ProvisioningToken;

@Repository
public interface ProvisioningTokenRepository extends JpaRepository<ProvisioningToken, Long> {
    Optional<ProvisioningToken> findByToken(String token);
}