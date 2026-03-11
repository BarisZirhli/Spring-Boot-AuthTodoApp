package com.example.notificationservice.listener;

import com.example.notificationservice.event.TodoEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TodoEventListener {

    private static final Logger log = LoggerFactory.getLogger(TodoEventListener.class);

    @KafkaListener(topics = "${app.kafka.topics.todo-events}")
    public void handle(TodoEvent event) {
        log.info("Todo event received: action={}, todoId={}, owner={}, completed={} occurredAt={}",
                event.getAction(),
                event.getTodoId(),
                event.getOwner(),
                event.isCompleted(),
                event.getOccurredAt());
    }
}
