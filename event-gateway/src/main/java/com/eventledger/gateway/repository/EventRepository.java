package com.eventledger.gateway.repository;

import com.eventledger.gateway.domain.StoredEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<StoredEvent, String> {

    /**
     * Events for one account, ordered by business time ascending. Sorting
     * happens here at query time — never by insertion order — so out-of-order
     * arrival still produces a correctly ordered listing (CLAUDE.md).
     */
    List<StoredEvent> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
