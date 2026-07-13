package com.kommserver.repository;

import com.kommserver.model.db.Server;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface ServerRepository extends JpaRepository<Server, UUID> {

    boolean existsByServerId(UUID serverId);

    List<Server> findByPendingDeletionTrue();
}