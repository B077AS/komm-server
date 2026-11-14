package com.kommserver.repository;

import com.kommserver.model.db.PollOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PollOptionRepository extends JpaRepository<PollOption, UUID> {

    List<PollOption> findByMessageIdInOrderByPositionAsc(Collection<UUID> messageIds);

    @Modifying
    @Query("DELETE FROM PollOption o WHERE o.messageId IN :messageIds")
    void deleteByMessageIdIn(@Param("messageIds") Collection<UUID> messageIds);
}
