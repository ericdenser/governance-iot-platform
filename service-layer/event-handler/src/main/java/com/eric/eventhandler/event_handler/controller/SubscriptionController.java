package com.eric.eventhandler.event_handler.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eric.eventhandler.event_handler.enums.EventType;
import com.eric.eventhandler.event_handler.model.dto.SubscriptionRequestDTO;
import com.eric.eventhandler.event_handler.model.dto.SubscriptionResultDTO;
import com.eric.eventhandler.event_handler.model.response.ApiResponse;
import com.eric.eventhandler.event_handler.service.SubscriberRegistry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/subscribe")
public class SubscriptionController {
    
    private final SubscriberRegistry subscriberRegistry;

    public SubscriptionController(SubscriberRegistry subscriberRegistry) {
        this.subscriberRegistry = subscriberRegistry;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<List<SubscriptionResultDTO>>> subscribeEvent(@Valid @RequestBody SubscriptionRequestDTO subscriptionRequestDTO, HttpServletRequest httpRequest) {

        List<SubscriptionResultDTO> result = subscriberRegistry.subscribe(
            subscriptionRequestDTO.subscriberName(), subscriptionRequestDTO.eventType(), subscriptionRequestDTO.webhookUrl());

        return ResponseEntity.ok(ApiResponse.success(result, httpRequest.getRequestURI()));
    }

    @GetMapping("/{subscriberId}")
    public ResponseEntity<ApiResponse<List<String>>> checkSubscriptions(@PathVariable Long subscriberId, HttpServletRequest httpRequest) {

        List<String> eventsFromSub = subscriberRegistry.listBySubscriber(subscriberId);

        return ResponseEntity.ok(ApiResponse.success(eventsFromSub, httpRequest.getRequestURI()));
    }

    @DeleteMapping("/{subscriberName}/{eventType}")
    public ResponseEntity<ApiResponse<String>> unsubscribe(@PathVariable String subscriberName, @PathVariable EventType eventType, HttpServletRequest httpRequest) {
        String result = subscriberRegistry.unsubscribe(subscriberName, eventType);

        return ResponseEntity.ok(ApiResponse.success(result, httpRequest.getRequestURI()));
    }

    @DeleteMapping("/{subscriberName}")
    public ResponseEntity<ApiResponse<String>> unsubscribeAll(@PathVariable String subscriberName, HttpServletRequest httpRequest) {
        String result = subscriberRegistry.unsubscribeAll(subscriberName);

        return ResponseEntity.ok(ApiResponse.success(result, httpRequest.getRequestURI()));
    }

}
