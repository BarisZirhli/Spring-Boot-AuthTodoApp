package com.example.todoservice.repository;

import com.example.todoservice.entity.Todo;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TodoRepository extends JpaRepository<Todo, Long>, JpaSpecificationExecutor<Todo> {
    Optional<Todo> findByIdAndOwnerUsernameAndDeletedAtIsNull(Long id, String username);
}
