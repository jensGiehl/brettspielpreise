package de.agiehl.bgprices.cache;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnapshotRepository extends JpaRepository<SnapshotEntity, String> {
    List<SnapshotEntity> findByLookupKey(String key);
    long deleteByExpiresAtBefore(Instant cutoff);
}
