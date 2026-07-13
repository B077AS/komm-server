package com.kommserver.repository;

import com.kommserver.model.db.ChannelRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelReadRepository extends JpaRepository<ChannelRead, ChannelRead.PK> {

    Optional<ChannelRead> findByUserIdAndChannelId(UUID userId, UUID channelId);

    List<ChannelRead> findAllByUserId(UUID userId);
}
