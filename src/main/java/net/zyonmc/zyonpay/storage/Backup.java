package net.zyonmc.zyonpay.storage;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Full dump and restore of every table as gzipped JSON, streamed so large histories fit in memory. */
public final class Backup {

    private static final int FETCH_SIZE = 1000;

    private final Database database;

    public Backup(Database database) {
        this.database = database;
    }

    public void write(Path file) {
        try (Connection c = database.connection();
             JsonWriter out = new JsonWriter(new OutputStreamWriter(
                     new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8))) {
            out.beginObject();
            out.name("version").value(Database.schemaVersion());
            section(c, out, "accounts", "SELECT uuid, name, accepts_payments FROM accounts", rs -> {
                out.name("uuid").value(uuid(rs, "uuid"));
                out.name("name").value(rs.getString("name"));
                out.name("accepts").value(rs.getBoolean("accepts_payments"));
            });
            section(c, out, "balances", "SELECT uuid, currency, balance, max_balance FROM balances", rs -> {
                out.name("uuid").value(uuid(rs, "uuid"));
                out.name("currency").value(rs.getString("currency"));
                out.name("balance").value(rs.getLong("balance"));
                long max = rs.getLong("max_balance");
                out.name("max").value(rs.wasNull() ? null : max);
            });
            section(c, out, "blocks", "SELECT owner, blocked FROM pay_blocks", rs -> {
                out.name("owner").value(uuid(rs, "owner"));
                out.name("blocked").value(uuid(rs, "blocked"));
            });
            section(c, out, "transactions",
                    "SELECT id, from_uuid, to_uuid, amount, type, server, created_at, currency, reason, reverted FROM transactions",
                    rs -> {
                        out.name("id").value(rs.getLong("id"));
                        out.name("from").value(uuid(rs, "from_uuid"));
                        out.name("to").value(uuid(rs, "to_uuid"));
                        out.name("amount").value(rs.getLong("amount"));
                        out.name("type").value(rs.getString("type"));
                        out.name("server").value(rs.getString("server"));
                        out.name("at").value(rs.getTimestamp("created_at").getTime());
                        out.name("currency").value(rs.getString("currency"));
                        out.name("reason").value(rs.getString("reason"));
                        out.name("reverted").value(rs.getBoolean("reverted"));
                    });
            out.endObject();
        } catch (IOException | SQLException e) {
            throw new StorageException("Backup failed: " + file, e);
        }
    }

    /** Replaces all data with the file's content in a single transaction. */
    public void restore(Path file) {
        try (JsonReader in = new JsonReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8));
             Connection c = database.connection()) {
            c.setAutoCommit(false);
            try {
                in.beginObject();
                expect(in, "version");
                int version = in.nextInt();
                if (version != Database.schemaVersion()) {
                    throw new StorageException("Backup schema version " + version + " does not match "
                            + Database.schemaVersion(), null);
                }
                for (String table : new String[]{"transactions", "pay_blocks", "balances", "accounts"}) {
                    try (var s = c.createStatement()) {
                        s.executeUpdate("DELETE FROM " + table);
                    }
                }
                load(c, in, "accounts", "INSERT INTO accounts (uuid, name, accepts_payments) VALUES (?, ?, ?)", (ps, r) -> {
                    ps.setBytes(1, bytes(r.uuid()));
                    ps.setString(2, r.string("name"));
                    ps.setBoolean(3, r.bool("accepts"));
                });
                load(c, in, "balances", "INSERT INTO balances (uuid, currency, balance, max_balance) VALUES (?, ?, ?, ?)",
                        (ps, r) -> {
                            ps.setBytes(1, bytes(r.uuid()));
                            ps.setString(2, r.string("currency"));
                            ps.setLong(3, r.number("balance"));
                            if (r.has("max")) {
                                ps.setLong(4, r.number("max"));
                            } else {
                                ps.setNull(4, Types.BIGINT);
                            }
                        });
                load(c, in, "blocks", "INSERT INTO pay_blocks (owner, blocked) VALUES (?, ?)", (ps, r) -> {
                    ps.setBytes(1, bytes(r.uuid("owner")));
                    ps.setBytes(2, bytes(r.uuid("blocked")));
                });
                load(c, in, "transactions",
                        "INSERT INTO transactions (id, from_uuid, to_uuid, amount, type, server, created_at, currency, reason, reverted) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        (ps, r) -> {
                            ps.setLong(1, r.number("id"));
                            setUuid(ps, 2, r.has("from") ? r.uuid("from") : null);
                            setUuid(ps, 3, r.has("to") ? r.uuid("to") : null);
                            ps.setLong(4, r.number("amount"));
                            ps.setString(5, r.string("type"));
                            ps.setString(6, r.string("server"));
                            ps.setTimestamp(7, new Timestamp(r.number("at")));
                            ps.setString(8, r.string("currency"));
                            ps.setString(9, r.has("reason") ? r.string("reason") : null);
                            ps.setBoolean(10, r.bool("reverted"));
                        });
                in.endObject();
                c.commit();
            } catch (IOException | SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (IOException | SQLException e) {
            throw new StorageException("Restore failed: " + file, e);
        }
    }

    // ---------------------------------------------------------------- writing

    private interface RowWriter {
        void write(ResultSet rs) throws IOException, SQLException;
    }

    private static void section(Connection c, JsonWriter out, String name, String sql, RowWriter row)
            throws IOException, SQLException {
        out.name(name).beginArray();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(FETCH_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.beginObject();
                    row.write(rs);
                    out.endObject();
                }
            }
        }
        out.endArray();
    }

    private static String uuid(ResultSet rs, String column) throws SQLException {
        UUID uuid = Uuids.fromBytes(rs.getBytes(column));
        return uuid == null ? null : uuid.toString();
    }

    // ---------------------------------------------------------------- reading

    /** One JSON object read into memory; null-valued fields are skipped so {@link #has} tells them apart. */
    private static final class Row {
        private final java.util.Map<String, String> fields = new java.util.HashMap<>();

        boolean has(String key) {
            return fields.containsKey(key);
        }

        String string(String key) {
            return fields.get(key);
        }

        long number(String key) {
            return Long.parseLong(fields.get(key));
        }

        boolean bool(String key) {
            return Boolean.parseBoolean(fields.get(key));
        }

        UUID uuid() {
            return uuid("uuid");
        }

        UUID uuid(String key) {
            return UUID.fromString(fields.get(key));
        }
    }

    private interface RowBinder {
        void bind(PreparedStatement ps, Row row) throws SQLException;
    }

    private static void load(Connection c, JsonReader in, String section, String sql, RowBinder binder)
            throws IOException, SQLException {
        expect(in, section);
        in.beginArray();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int pending = 0;
            while (in.hasNext()) {
                Row row = new Row();
                in.beginObject();
                while (in.hasNext()) {
                    String key = in.nextName();
                    switch (in.peek()) {
                        case NULL -> in.nextNull();
                        case BOOLEAN -> row.fields.put(key, String.valueOf(in.nextBoolean()));
                        default -> row.fields.put(key, in.nextString());
                    }
                }
                in.endObject();
                binder.bind(ps, row);
                ps.addBatch();
                if (++pending == FETCH_SIZE) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
        }
        in.endArray();
    }

    private static void expect(JsonReader in, String name) throws IOException {
        String actual = in.nextName();
        if (!actual.equals(name)) {
            throw new IOException("Unexpected backup section '" + actual + "', expected '" + name + "'");
        }
    }

    private static byte[] bytes(UUID uuid) {
        return Uuids.toBytes(uuid);
    }

    private static void setUuid(PreparedStatement ps, int index, UUID uuid) throws SQLException {
        if (uuid == null) {
            ps.setNull(index, Types.BINARY);
        } else {
            ps.setBytes(index, Uuids.toBytes(uuid));
        }
    }
}
