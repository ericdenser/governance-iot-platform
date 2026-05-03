package com.eric.governanceApi.governanceApi.model;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "provisioning_tokens")
@Data
@NoArgsConstructor
public class ProvisioningToken {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String token; // UUID

    @Column(nullable = false)
    private boolean used = false;

    @Column(name = "expires_at")
    private LocalDateTime exp;

    @Column(name = "issued_at")
    private LocalDateTime iat;

    @OneToOne
    @JoinColumn(name = "device_id")
    private Device device;

    public void validate() {
        if (this.used) {
            throw new SecurityException("Token already used.");
            
        }
        if (this.exp.isBefore(LocalDateTime.now())) {
            throw new SecurityException("Token expired.");
        }
    }

    public ProvisioningToken(Device device, int lifeTimeInSeconds) {
        this.device = device;
        this.token = UUID.randomUUID().toString().replace("-", "");
        this.iat = LocalDateTime.now();
        this.exp = this.iat.plusSeconds(lifeTimeInSeconds);
    }
}
