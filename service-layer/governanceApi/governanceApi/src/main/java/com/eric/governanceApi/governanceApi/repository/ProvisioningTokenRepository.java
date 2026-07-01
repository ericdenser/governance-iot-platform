package com.eric.governanceApi.governanceApi.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.eric.governanceApi.governanceApi.model.entity.ProvisioningToken;

@Repository
public interface ProvisioningTokenRepository extends JpaRepository<ProvisioningToken, Long> {

    Optional<ProvisioningToken> findByToken(String token);

    /** Tokens cujo prazo venceu e que nunca foram utilizados para provisionar um device. */
    List<ProvisioningToken> findByExpBeforeAndUsedFalse(Instant threshold);
}