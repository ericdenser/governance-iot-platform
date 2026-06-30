package com.eric.eventhandler.event_handler.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.eric.eventhandler.event_handler.enums.EventType;
import com.eric.eventhandler.event_handler.exception.ResourceNotFoundException;
import com.eric.eventhandler.event_handler.model.dto.SubscriptionResultDTO;
import com.eric.eventhandler.event_handler.model.entity.EventSubscription;
import com.eric.eventhandler.event_handler.repository.EventSubscriptionRepository;
import com.eric.eventhandler.event_handler.repository.SubscriberRepository;
import com.eric.eventhandler.event_handler.model.entity.Subscriber;

import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SubscriberRegistry {
    
    private final EventSubscriptionRepository eventSubRepository;
    private final SubscriberRepository subRepository;

    private final ConcurrentHashMap<EventType, Set<String>> cache = new ConcurrentHashMap<>();

    public SubscriberRegistry(EventSubscriptionRepository eventSubRepository, SubscriberRepository subRepository) {
        this.eventSubRepository = eventSubRepository;
        this.subRepository = subRepository;
    }

    @PostConstruct
    public void loadFromDatabase() {
        List<EventSubscription> allSubs = eventSubRepository.findAll();
        rebuildCache(allSubs);

         log.info("SubscriptionRegistry searched — {} active subscribers in {} event types",
                 allSubs.stream().filter(EventSubscription::isActive).count(),
                 cache.size());
    }

    public Set<String> getWebhooksFor(EventType eventType) {
        return Set.copyOf(cache.getOrDefault(eventType, Set.of())
        );
    }

    @Transactional
    public  List<SubscriptionResultDTO> subscribe (String subscriberName, List<EventType> eventsType, String webhookUrl) {

        List<SubscriptionResultDTO> result = new ArrayList<>();

        // Verificamos se existe quem fez a requisicao é um subscriber registrado
        Optional<Subscriber> subscriberOptional = subRepository.findBySubscriberName(subscriberName);

        Subscriber subscriber;
        
        // Nunca havia se inscrito em nada, registramos sua inscricao na rede de eventos
        if (subscriberOptional.isEmpty()) { 
            log.info("New subscriber detected. Creating profile for {}", subscriberName);
            
            Subscriber newSub = new Subscriber();
            newSub.setSubscriberName(subscriberName);
            
            subscriber = subRepository.save(newSub);
        // Subscriber ja registrado, prosseguimos
        } else {
            // Ja existe
            subscriber = subscriberOptional.get();
        }

        for (EventType event : eventsType) {

            // Procuramos se ja há alguma inscricao deste subscriber no evento da requisição.
            Optional<EventSubscription> existing = eventSubRepository.findBySubscriber_SubscriberNameAndEventType(subscriberName, event);

            
            EventSubscription sub;

            // Subscriber ja esta inscrito neste evento
            if (existing.isPresent()) {
                sub = existing.get();

                // Se a url da requisição for a mesma salva
                if (sub.getWebhookUrl().equals(webhookUrl) && sub.isActive()) {
                    log.info("Subscription: {} -> {} in {} already exists, ignoring.", subscriberName, event, webhookUrl);


                    result.add(new SubscriptionResultDTO(event.toString(), false,  "This subscription is already registered."));
                    continue;
                }

                // Se for uma url diferente, atualizamos a inscrição
                sub.setWebhookUrl(webhookUrl);
                sub.setActive(true);
                log.info("Subscription updated: {} -> {} in {}", subscriberName, event, webhookUrl);

                result.add(new SubscriptionResultDTO(event.toString(), true, "Subscription webhook successfully updated."));

            // Subscriber nao estava inscrito neste evento ainda
            } else {

            
                sub = new EventSubscription();
                sub.setSubscriber(subscriber);
                sub.setEventType(event);
                sub.setWebhookUrl(webhookUrl);

                subscriber.getEventsSubscribed().add(sub);
                
                log.info("New signature: {} → {} in {}", subscriberName, event, webhookUrl);
                eventSubRepository.save(sub);

                result.add(new SubscriptionResultDTO(event.toString(), true, "Subscription successfully registered."));
            }
        }
    
        refreshCache();
        return result;
    }

    public String unsubscribe(String subscriberName, EventType eventType) {
        // Atualizado para usar o novo método do repositório
        EventSubscription sub = eventSubRepository.findBySubscriber_SubscriberNameAndEventType(subscriberName, eventType)
        .orElseThrow(() -> new ResourceNotFoundException("Subscription not found: " + subscriberName + " -> " + eventType));
        
        sub.setActive(false);
        eventSubRepository.save(sub);
        refreshCache();

        log.info("Subscription deactivated: {} -> {}", subscriberName, eventType);
        return "Subscription deactivated: " + subscriberName + " -> " + eventType;
    }

    public String unsubscribeAll(String subscriberName) {
        // Atualizado para usar o novo método do repositório
        List<EventSubscription> subs = eventSubRepository.findBySubscriber_SubscriberName(subscriberName);

        if (subs.isEmpty()) {
            log.info("No subscriptions found for this subscriber [{}]", subscriberName);
            throw new ResourceNotFoundException("No subscriptions found for this subscriber");
        }

        subs.forEach(sub -> sub.setActive(false));
        eventSubRepository.saveAll(subs);

        log.info("All signatures from [{}] deactivated ({} total)", subscriberName, subs.size());
        refreshCache();

        return "All signatures from " + subscriberName + " deactivated";
    }

    public List<EventSubscription> listAll() {
        return eventSubRepository.findAll();
    }

    public List<String> listBySubscriber(Long id) {
        List<EventSubscription> subs = eventSubRepository.findBySubscriberId(id);
        
        // Mapeia as inscrições para pegar apenas o nome do evento em formato de String
        return subs.stream()
                   .map(sub -> sub.getEventType().name())
                   .toList(); 
    }

    private void refreshCache() {
        rebuildCache(eventSubRepository.findAll());
        log.info("Cache atual: {}", cache);
    }
    
    private void rebuildCache(List<EventSubscription> allSubs) {

        cache.clear();
        
        allSubs.stream().filter(EventSubscription::isActive)
                        .forEach(sub -> {
                            cache.computeIfAbsent(sub.getEventType(), 
                            k -> ConcurrentHashMap.newKeySet()).add(sub.getWebhookUrl());
                        });

    }

}
