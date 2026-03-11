package com.example.todoservice.service;

import com.example.todoservice.dto.TodoRequest;
import com.example.todoservice.dto.TodoResponse;
import com.example.todoservice.entity.Todo;
import com.example.todoservice.event.TodoEvent;
import com.example.todoservice.event.TodoEventPublisher;
import com.example.todoservice.repository.TodoRepository;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class TodoService {

    private final TodoRepository todoRepository;
    private final TodoEventPublisher todoEventPublisher;

    public TodoService(TodoRepository todoRepository, TodoEventPublisher todoEventPublisher) {
        this.todoRepository = todoRepository;
        this.todoEventPublisher = todoEventPublisher;
    }

    public Page<TodoResponse> getAll(String username, Boolean completed, String query, Pageable pageable) {
        Specification<Todo> spec = Specification
                .where(ownerIs(username))
                .and(notDeleted())
                .and(hasCompleted(completed))
                .and(searches(query));
        return todoRepository.findAll(spec, pageable).map(this::mapToResponse);
    }

    public TodoResponse getById(Long id, String username) {
        Todo todo = todoRepository.findByIdAndOwnerUsernameAndDeletedAtIsNull(id, username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo not found"));
        return mapToResponse(todo);
    }

    @Transactional
    public TodoResponse create(TodoRequest request, String username) {
        Todo todo = new Todo();
        todo.setTitle(normalizeTitle(request.getTitle()));
        todo.setDescription(normalizeDescription(request.getDescription()));
        todo.setCompleted(request.isCompleted());
        todo.setOwnerUsername(username);
        Todo saved = todoRepository.save(todo);
        todoEventPublisher.publish("CREATED", mapToEvent(saved));
        return mapToResponse(saved);
    }

    @Transactional
    public TodoResponse update(Long id, TodoRequest request, String username) {
        Todo todo = todoRepository.findByIdAndOwnerUsernameAndDeletedAtIsNull(id, username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo not found"));

        todo.setTitle(normalizeTitle(request.getTitle()));
        todo.setDescription(normalizeDescription(request.getDescription()));
        todo.setCompleted(request.isCompleted());
        Todo saved = todoRepository.save(todo);
        todoEventPublisher.publish("UPDATED", mapToEvent(saved));
        return mapToResponse(saved);
    }

    @Transactional
    public void delete(Long id, String username) {
        Todo todo = todoRepository.findByIdAndOwnerUsernameAndDeletedAtIsNull(id, username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo not found"));
        todo.setDeletedAt(Instant.now());
        todo.setDeletedBy(username);
        Todo saved = todoRepository.save(todo);
        todoEventPublisher.publish("DELETED", mapToEvent(saved));
    }

    private TodoResponse mapToResponse(Todo todo) {
        TodoResponse response = new TodoResponse();
        response.setId(todo.getId());
        response.setVersion(todo.getVersion());
        response.setTitle(todo.getTitle());
        response.setDescription(todo.getDescription());
        response.setCompleted(todo.isCompleted());
        response.setOwner(todo.getOwnerUsername());
        response.setCreatedAt(todo.getCreatedAt());
        response.setUpdatedAt(todo.getUpdatedAt());
        return response;
    }

    private TodoEvent mapToEvent(Todo todo) {
        TodoEvent event = new TodoEvent();
        event.setTodoId(todo.getId());
        event.setTitle(todo.getTitle());
        event.setDescription(todo.getDescription());
        event.setCompleted(todo.isCompleted());
        event.setOwner(todo.getOwnerUsername());
        return event;
    }

    private String normalizeTitle(String title) {
        return title == null ? null : title.trim();
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }

        String normalized = description.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Specification<Todo> ownerIs(String username) {
        return (root, query, cb) -> cb.equal(root.get("ownerUsername"), username);
    }

    private Specification<Todo> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    private Specification<Todo> hasCompleted(Boolean completed) {
        if (completed == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("completed"), completed);
    }

    private Specification<Todo> searches(String queryValue) {
        if (queryValue == null || queryValue.isBlank()) {
            return null;
        }
        String pattern = "%" + queryValue.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern),
                cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern)
        );
    }
}
