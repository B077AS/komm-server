package com.kommserver.repository;

import com.kommserver.model.db.Installation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface InstallationRepository extends JpaRepository<Installation, UUID> {

}