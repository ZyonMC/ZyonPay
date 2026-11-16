package net.zyonmc.zyonpay.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A history row seen from one player. {@code otherName} is the other party's name, or null for the system.
 */
public record HistoryRow(long id, UUID from, UUID to, long amount, TxType type, String server, Instant at,
                         String otherName, String currency, String reason, boolean reverted) {

    public boolean isIncoming(UUID viewer) {
        return viewer.equals(to);
    }
}
