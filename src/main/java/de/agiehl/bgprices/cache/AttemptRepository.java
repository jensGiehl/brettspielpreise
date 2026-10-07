package de.agiehl.bgprices.cache;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepository extends JpaRepository<AttemptEntity, String> {
    long deleteByAttemptedAtBefore(Instant cutoff);
}
