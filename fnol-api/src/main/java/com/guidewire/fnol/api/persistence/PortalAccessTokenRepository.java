package com.guidewire.fnol.api.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PortalAccessTokenRepository extends JpaRepository<PortalAccessTokenEntity, String> {
}
