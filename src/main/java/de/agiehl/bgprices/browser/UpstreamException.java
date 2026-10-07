package de.agiehl.bgprices.browser;

import de.agiehl.bgprices.domain.FailureCode;

public class UpstreamException extends RuntimeException {
    private final FailureCode code;

    public UpstreamException(FailureCode code) {
        super(code.name());
        this.code = code;
    }

    public FailureCode code() { return code; }
}
