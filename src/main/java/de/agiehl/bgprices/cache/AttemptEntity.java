package de.agiehl.bgprices.cache;

import de.agiehl.bgprices.domain.FailureCode;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "lookup_attempt")
public class AttemptEntity {
    @Id @Column(length = 64) private String lookupKey;
    @Column(nullable = false) private Instant attemptedAt;
    @Enumerated(EnumType.STRING) @Column(length = 40) private FailureCode failureCode;
    private Instant retryAt;

    protected AttemptEntity() { }

    public AttemptEntity(String key, Instant time, FailureCode failure, Instant retry) {
        lookupKey = key;
        attemptedAt = time;
        failureCode = failure;
        retryAt = retry;
    }

    public Instant attemptedAt() { return attemptedAt; }
    public FailureCode failureCode() { return failureCode; }
    public Instant retryAt() { return retryAt; }
}
