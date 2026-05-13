package com.eric.eventhandler.event_handler.service;

import org.springframework.stereotype.Service;

import com.eric.eventhandler.event_handler.model.DeviceEvent;
import com.eric.eventhandler.event_handler.model.StatusDTO;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EventManagerService {
    


    private final TransitionDetector detector;
    private final EventDispatcher dispatcher;

    public EventManagerService(TransitionDetector detector, EventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.detector = detector;
    }

    public void handleStatus(StatusDTO statusDto) {
            log.info("Status recebido: MAC={} status={} fw=v{}",
                statusDto.mac(), statusDto.status(), statusDto.firmware_version());

        // Procura se transição atual dispara algum evento predefindo
        DeviceEvent event = detector.process(statusDto);

        // Transição não disparou nenhum evento
        if (event == null) {
            log.info("Transição: {} → {} não disparou nenhum evento" + "previousStatus, newStatus)");
            return;
        }

        // Transição disparou um evento
        dispatcher.dispatch(event);
        return;
    }
}
