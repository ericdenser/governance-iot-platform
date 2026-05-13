package com.eric.eventhandler.event_handler.service;

import org.springframework.stereotype.Service;

import com.eric.eventhandler.event_handler.model.DeviceEvent;

@Service
public class EventDispatcher {
    


    public EventDispatcher() {

    }

    public void dispatch(DeviceEvent event) {
        persistLog(event);
        deliverToSubscribers(event);
    }

    private void persistLog(event) {

    }


    private void deliverToSubscribers(event) {

    }

}
