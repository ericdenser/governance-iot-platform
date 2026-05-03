package com.eric.governanceApi.governanceApi.enums;

public enum FirmwareStatus {
    STAGED,      // Subiu pro servidor, nunca foi deployado ainda
    DEPLOYED,    // Já foi enviado para pelo menos 1 device
    DEPRECATED   // Marcado como obsoleto, não pode mais ser deployado
}