package com.eric.eventhandler.event_handler.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.eric.eventhandler.event_handler.model.entity.Subscriber;

public interface SubscriberRepository extends JpaRepository<Subscriber, Long> {
    Optional<Subscriber> findBySubscriberName(String subscriberName);
}
