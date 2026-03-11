package com.example.todoservice.event;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class TodoEventPublisher {

    private final KafkaTemplate<String, TodoEvent> kafkaTemplate;
    private final String topicName;

    public TodoEventPublisher(
            KafkaTemplate<String, TodoEvent> kafkaTemplate,
            @Value("${app.kafka.topics.todo-events}") String topicName
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicName = topicName;
    }

    public void publish(String action, TodoEvent payload) {
        payload.setAction(action);
        payload.setOccurredAt(Instant.now());
        kafkaTemplate.send(topicName, payload.getOwner(), payload);
    }
}
