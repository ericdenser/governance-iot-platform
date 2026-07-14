package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

/**
 * Payload minimal para renderização do mapa em tempo real.
 *
 * Só o essencial: identidade e localização atual. Nenhum campo de firmware,
 * grupo, sensores ou histórico — mapa só precisa desenhar marker e mostrar
 * "visto há N segundos" no tooltip.
 *
 * Todos os campos vêm do Redis Hash (device:{id}:last), consultado via
 * HotStateService.getLiveBulk. Devices sem coord válida (lat OU lon null)
 * são filtrados no service antes de compor essa resposta.
 */
public record DeviceMapPositionDTO(
    String deviceId,
    Double latitude,
    Double longitude,
    Instant lastSeen
) {}
