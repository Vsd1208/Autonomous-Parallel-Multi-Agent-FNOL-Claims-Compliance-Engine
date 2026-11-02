package com.guidewire.fnol.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface UserSessionRepository extends JpaRepository<UserSessionEntity, String> {
    @Transactional
    void deleteByUsername(String username);
}
