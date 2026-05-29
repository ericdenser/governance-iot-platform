package com.eric.eventhandler.event_handler.model.entity;

import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "subscribers", uniqueConstraints = @UniqueConstraint(columnNames = {"subscriberName"}))
@Data
@NoArgsConstructor
public class Subscriber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String subscriberName;

    @OneToMany(mappedBy = "subscriber", cascade = CascadeType.ALL, orphanRemoval = true)
    // CORREÇÃO 4: Inicializar a lista com '= new ArrayList<>()' previne o erro NullPointerException
    // quando você tentar fazer 'subscriber.getEventsSubscribed().add(sub)' em um novo cliente.
    private List<EventSubscription> eventsSubscribed = new ArrayList<>();
}