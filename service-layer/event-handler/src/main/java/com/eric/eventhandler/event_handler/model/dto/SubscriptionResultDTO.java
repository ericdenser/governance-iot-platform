package com.eric.eventhandler.event_handler.model.dto;

public record SubscriptionResultDTO (
    String eventName,
    Boolean subscriptionSuccess,
    String message

){
    

}
