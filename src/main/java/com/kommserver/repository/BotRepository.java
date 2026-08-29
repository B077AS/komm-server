package com.kommserver.repository;

import com.kommserver.model.db.Bot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BotRepository extends JpaRepository<Bot, UUID> {

    List<Bot> findByServerId(UUID serverId);

    long countByServerId(UUID serverId);
}
