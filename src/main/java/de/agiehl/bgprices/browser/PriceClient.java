package de.agiehl.bgprices.browser;

import de.agiehl.bgprices.domain.LiveResult;
import de.agiehl.bgprices.domain.Lookup;

public interface PriceClient extends AutoCloseable {
    LiveResult fetch(Lookup lookup, Deadline deadline);
    @Override void close();
}
