package com.guidewire.fnol.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiFeedbackRepository extends JpaRepository<AiFeedbackEntity, Long> {

    List<AiFeedbackEntity> findAllByOrderByDecidedAtAsc();
}
