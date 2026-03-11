package com.example.todoservice.controller;

import com.example.todoservice.dto.PagedResponse;
import com.example.todoservice.dto.TodoRequest;
import com.example.todoservice.dto.TodoResponse;
import com.example.todoservice.service.TodoService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService todoService;

    public TodoController(TodoService todoService) {
        this.todoService = todoService;
    }

    @GetMapping
    public PagedResponse<TodoResponse> getTodos(Authentication authentication,
                                                @RequestParam(required = false) Boolean completed,
                                                @RequestParam(required = false, name = "q") String query,
                                                @PageableDefault(size = 10, sort = "createdAt",
                                                        direction = Sort.Direction.DESC) Pageable pageable) {
        Page<TodoResponse> page = todoService.getAll(authentication.getName(), completed, query, pageable);
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    @GetMapping("/{id}")
    public TodoResponse getTodo(@PathVariable Long id, Authentication authentication) {
        return todoService.getById(id, authentication.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TodoResponse create(@Valid @RequestBody TodoRequest request, Authentication authentication) {
        return todoService.create(request, authentication.getName());
    }

    @PutMapping("/{id}")
    public TodoResponse update(@PathVariable Long id,
                               @Valid @RequestBody TodoRequest request,
                               Authentication authentication) {
        return todoService.update(id, request, authentication.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        todoService.delete(id, authentication.getName());
    }
}
