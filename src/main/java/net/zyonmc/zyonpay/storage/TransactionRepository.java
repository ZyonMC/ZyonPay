package net.zyonmc.zyonpay.storage;

import net.zyonmc.zyonpay.model.HistoryRow;
import net.zyonmc.zyonpay.model.Transaction;
import net.zyonmc.zyonpay.model.TxDetail;
import net.zyonmc.zyonpay.model.TxType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL access to the {@code transactions} table. Blocks: call off the main thread. */
public final class TransactionRepository {

    private static final int REASON_MAX = 255;
    private static final int PURGE_BATCH = 5000;

    private static final String HISTORY_SELECT = """
            SELECT t.id, t.from_uuid, t.to_uuid, t.amount, t.type, t.server, t.created_at, t.currency, t.reason,
                   t.reverted, a.name AS other_name
            FROM transactions t
            LEFT JOIN accounts a ON a.uuid = IF(t.from_uuid = ?, t.to_uuid, t.from_uuid)
            """;

    private final Database database;

    public TransactionRepository(Database database) {
        this.database = database;
    }

    public void insertBatch(List<Transaction> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try (Connection c = database.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactions (from_uuid, to_uuid, amount, type, server, created_at, currency, reason) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Transaction tx : batch) {
                    setNullableUuid(ps, 1, tx.from());
                    setNullableUuid(ps, 2, tx.to());
                    ps.setLong(3, tx.amount());
                    ps.setString(4, tx.type().name());
                    ps.setString(5, tx.server());
                    ps.setTimestamp(6, Timestamp.from(tx.at()));
                    ps.setString(7, tx.currency());
                    ps.setString(8, truncate(tx.reason()));
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StorageException("insertBatch failed (" + batch.size() + " transactions)", e);
        }
    }

    /** Latest transactions involving {@code player}, newest first. */
    public List<HistoryRow> recent(UUID player, int limit) {
        return between(player, null, null, limit);
    }

    /**
     * Transactions of {@code player} between two instants, newest first.
     *
     * @param before only those older than this instant (null = no limit)
     * @param after only those newer than this instant (null = no limit)
     */
    public List<HistoryRow> between(UUID player, Instant before, Instant after, int limit) {
        StringBuilder sql = new StringBuilder(HISTORY_SELECT).append("WHERE (t.from_uuid = ? OR t.to_uuid = ?)");
        if (before != null) {
            sql.append(" AND t.created_at < ?");
        }
        if (after != null) {
            sql.append(" AND t.created_at > ?");
        }
        sql.append(" ORDER BY t.id DESC LIMIT ?");
        byte[] id = Uuids.toBytes(player);
        try (Connection c = database.connection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int index = 1;
            ps.setBytes(index++, id);
            ps.setBytes(index++, id);
            ps.setBytes(index++, id);
            if (before != null) {
                ps.setTimestamp(index++, Timestamp.from(before));
            }
            if (after != null) {
                ps.setTimestamp(index++, Timestamp.from(after));
            }
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<HistoryRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new HistoryRow(
                            rs.getLong("id"),
                            Uuids.fromBytes(rs.getBytes("from_uuid")),
                            Uuids.fromBytes(rs.getBytes("to_uuid")),
                            rs.getLong("amount"),
                            TxType.valueOf(rs.getString("type")),
                            rs.getString("server"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("other_name"),
                            rs.getString("currency"),
                            rs.getString("reason"),
                            rs.getBoolean("reverted")));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new StorageException("history failed for " + player, e);
        }
    }

    /** One transaction by id, with both parties' names. */
    public Optional<TxDetail> find(long id) {
        String sql = """
                SELECT t.id, t.from_uuid, t.to_uuid, t.amount, t.type, t.server, t.created_at, t.currency, t.reason,
                       t.reverted, f.name AS from_name, o.name AS to_name
                FROM transactions t
                LEFT JOIN accounts f ON f.uuid = t.from_uuid
                LEFT JOIN accounts o ON o.uuid = t.to_uuid
                WHERE t.id = ?
                """;
        try (Connection c = database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TxDetail(
                        rs.getLong("id"),
                        Uuids.fromBytes(rs.getBytes("from_uuid")),
                        Uuids.fromBytes(rs.getBytes("to_uuid")),
                        rs.getString("from_name"),
                        rs.getString("to_name"),
                        rs.getLong("amount"),
                        TxType.valueOf(rs.getString("type")),
                        rs.getString("server"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("currency"),
                        rs.getString("reason"),
                        rs.getBoolean("reverted")));
            }
        } catch (SQLException e) {
            throw new StorageException("find failed for transaction " + id, e);
        }
    }

    /** Atomically marks the transaction as reverted. False if it already was (or does not exist). */
    public boolean markReverted(long id) {
        return setReverted(id, true);
    }

    /** Undoes {@link #markReverted} when the reversal could not be applied. */
    public void unmarkReverted(long id) {
        setReverted(id, false);
    }

    private boolean setReverted(long id, boolean reverted) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("UPDATE transactions SET reverted = ? WHERE id = ? AND reverted = ?")) {
            ps.setBoolean(1, reverted);
            ps.setLong(2, id);
            ps.setBoolean(3, !reverted);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new StorageException("setReverted failed for transaction " + id, e);
        }
    }

    /** Deletes a currency's history older than {@code cutoff}, in batches. Returns how many rows were deleted. */
    public long purgeOlderThan(String currency, Instant cutoff) {
        long total = 0;
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM transactions WHERE currency = ? AND created_at < ? LIMIT " + PURGE_BATCH)) {
            int deleted;
            do {
                ps.setString(1, currency);
                ps.setTimestamp(2, Timestamp.from(cutoff));
                deleted = ps.executeUpdate();
                total += deleted;
            } while (deleted == PURGE_BATCH);
        } catch (SQLException e) {
            throw new StorageException("history purge failed for " + currency, e);
        }
        return total;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.length() <= REASON_MAX ? reason : reason.substring(0, REASON_MAX);
    }

    private static void setNullableUuid(PreparedStatement ps, int index, UUID uuid) throws SQLException {
        if (uuid == null) {
            ps.setNull(index, Types.BINARY);
        } else {
            ps.setBytes(index, Uuids.toBytes(uuid));
        }
    }
}
