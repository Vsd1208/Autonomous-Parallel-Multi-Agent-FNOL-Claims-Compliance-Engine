package com.guidewire.fnol.api.persistence;
import org.springframework.data.jpa.repository.*;
public interface AuditRepository extends JpaRepository<AuditEntity,String>{}
