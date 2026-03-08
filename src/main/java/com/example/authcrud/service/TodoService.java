package com.example.authcrud.service;

import com.example.authcrud.dto.TodoRequest;
import com.example.authcrud.dto.TodoResponse;
import com.example.authcrud.entity.AppUser;
import com.example.authcrud.entity.Todo;
import com.example.authcrud.repository.AppUserRepository;
import com.example.authcrud.repository.TodoRepository;
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
    private final AppUserRepository appUserRepository;

    public TodoService(TodoRepository todoRepository, AppUserRepository appUserRepository) {
        this.todoRepository = todoRepository;
        this.appUserRepository = appUserRepository;
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
        AppUser owner = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Todo todo = new Todo();
        todo.setTitle(normalizeTitle(request.getTitle()));
        todo.setDescription(normalizeDescription(request.getDescription()));
        todo.setCompleted(request.isCompleted());
        todo.setOwner(owner);

        return mapToResponse(todoRepository.save(todo));
    }

    @Transactional
    public TodoResponse update(Long id, TodoRequest request, String username) {
        Todo todo = todoRepository.findByIdAndOwnerUsernameAndDeletedAtIsNull(id, username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo not found"));

        todo.setTitle(normalizeTitle(request.getTitle()));
        todo.setDescription(normalizeDescription(request.getDescription()));
        todo.setCompleted(request.isCompleted());

        return mapToResponse(todoRepository.save(todo));
    }

    @Transactional
    public void delete(Long id, String username) {
        Todo todo = todoRepository.findByIdAndOwnerUsernameAndDeletedAtIsNull(id, username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Todo not found"));
        todo.setDeletedAt(Instant.now());
        todo.setDeletedBy(username);
        todoRepository.save(todo);
    }

    private TodoResponse mapToResponse(Todo todo) {
        TodoResponse response = new TodoResponse();
        response.setId(todo.getId());
        response.setVersion(todo.getVersion());
        response.setTitle(todo.getTitle());
        response.setDescription(todo.getDescription());
        response.setCompleted(todo.isCompleted());
        response.setOwner(todo.getOwner().getUsername());
        response.setCreatedAt(todo.getCreatedAt());
        response.setUpdatedAt(todo.getUpdatedAt());
        return response;
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
        return (root, query, cb) -> cb.equal(root.get("owner").get("username"), username);
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
