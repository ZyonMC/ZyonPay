package net.zyonmc.zyonpay.storage;

import net.zyonmc.zyonpay.model.AccountRow;
import net.zyonmc.zyonpay.money.Currency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Admin operations on the database (purge, migration, currency swap). They block. */
public final class MaintenanceRepository {

    /** Temporary currency name used to swap two currencies; {@code ~} is not valid in an id. */
    private static final String SWAP_TMP = "~swap~";

    private final Database database;

    public MaintenanceRepository(Database database) {
        this.database = database;
    }

    /** Accounts whose whole name matches the (Java) regex. */
    public List<AccountRow> accountsMatching(Pattern pattern) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, name, accepts_payments FROM accounts");
             ResultSet rs = ps.executeQuery()) {
            List<AccountRow> matches = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString("name");
                if (pattern.matcher(name).matches()) {
                    matches.add(new AccountRow(Uuids.fromBytes(rs.getBytes("uuid")), name, rs.getBoolean("accepts_payments")));
                }
            }
            return matches;
        } catch (SQLException e) {
            throw new StorageException("accountsMatching failed", e);
        }
    }

    /** Deletes accounts with their balances and blocks, keeping history. Returns how many accounts were deleted. */
    public int deleteAccounts(Collection<UUID> uuids) {
        if (uuids.isEmpty()) {
            return 0;
        }
        try (Connection c = database.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement balances = c.prepareStatement("DELETE FROM balances WHERE uuid = ?");
                 PreparedStatement blocks = c.prepareStatement("DELETE FROM pay_blocks WHERE owner = ? OR blocked = ?");
                 PreparedStatement accounts = c.prepareStatement("DELETE FROM accounts WHERE uuid = ?")) {
                for (UUID uuid : uuids) {
                    byte[] id = Uuids.toBytes(uuid);
                    balances.setBytes(1, id);
                    balances.addBatch();
                    blocks.setBytes(1, id);
                    blocks.setBytes(2, id);
                    blocks.addBatch();
                    accounts.setBytes(1, id);
                    accounts.addBatch();
                }
                balances.executeBatch();
                blocks.executeBatch();
                int deleted = 0;
                for (int count : accounts.executeBatch()) {
                    deleted += Math.max(count, 0);
                }
                c.commit();
                return deleted;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StorageException("deleteAccounts failed", e);
        }
    }

    /**
     * Creates the account with {@code balance} if it does not exist (to migrate from another plugin).
     *
     * @return false if the account already existed and was left untouched
     */
    public boolean importAccount(UUID uuid, String name, Currency currency, long balance) {
        try (Connection c = database.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement account = c.prepareStatement("INSERT IGNORE INTO accounts (uuid, name) VALUES (?, ?)");
                 PreparedStatement wallet = c.prepareStatement(
                         "INSERT IGNORE INTO balances (uuid, currency, balance) VALUES (?, ?, ?)")) {
                account.setBytes(1, Uuids.toBytes(uuid));
                account.setString(2, name);
                if (account.executeUpdate() != 1) {
                    c.rollback();
                    return false;
                }
                wallet.setBytes(1, Uuids.toBytes(uuid));
                wallet.setString(2, currency.id());
                wallet.setLong(3, Math.min(balance, currency.maxBalance()));
                wallet.executeUpdate();
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StorageException("importAccount failed for " + uuid, e);
        }
    }

    /** Swaps the balances and history of two currencies in one transaction. */
    public void swapCurrencies(String a, String b) {
        try (Connection c = database.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("UPDATE balances SET currency = ? WHERE currency = ?");
                 PreparedStatement tx = c.prepareStatement("UPDATE transactions SET currency = ? WHERE currency = ?")) {
                for (PreparedStatement statement : new PreparedStatement[]{ps, tx}) {
                    rename(statement, a, SWAP_TMP);
                    rename(statement, b, a);
                    rename(statement, SWAP_TMP, b);
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StorageException("swapCurrencies failed", e);
        }
    }

    private static void rename(PreparedStatement statement, String from, String to) throws SQLException {
        statement.setString(1, to);
        statement.setString(2, from);
        statement.executeUpdate();
    }
}
