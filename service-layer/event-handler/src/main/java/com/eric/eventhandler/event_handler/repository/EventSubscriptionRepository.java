package com.eric.eventhandler.event_handler.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import com.eric.eventhandler.event_handler.enums.EventType;
import com.eric.eventhandler.event_handler.model.entity.EventSubscription;

public interface EventSubscriptionRepository extends JpaRepository<EventSubscription, Long> {
    
    Optional<EventSubscription> findBySubscriber_SubscriberNameAndEventType(String subscriberName, EventType eventType);

    List<EventSubscription> findBySubscriber_SubscriberName(String subscriberName);

    // Busca pelas inscrições usando o ID do Subscriber (que vamos usar no listBySubscriber)
    List<EventSubscription> findBySubscriberId(Long subscriberId);

}   