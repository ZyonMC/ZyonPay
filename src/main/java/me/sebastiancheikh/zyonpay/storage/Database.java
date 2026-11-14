package me.sebastiancheikh.zyonpay.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.sebastiancheikh.zyonpay.config.Settings.DatabaseSettings;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** MariaDB connection pool and schema migrations. */
public final class Database implements AutoCloseable {

    /** A schema migration. {@code defaultCurrency} owns the data that existed before currencies. */
    private interface Migration {
        void run(Statement statement, String defaultCurrency) throws SQLException;
    }

    private static Migration sql(String... statements) {
        return (statement, defaultCurrency) -> {
            for (String sql : statements) {
                statement.execute(sql);
            }
        };
    }

    /** Index + 1 of each entry is its schema version. Never edit an existing one: add another. */
    private static final List<Migration> MIGRATIONS = List.of(
            sql(
                    """
                    CREATE TABLE accounts (
                      uuid BINARY(16) NOT NULL PRIMARY KEY,
                      name VARCHAR(32) NOT NULL,
                      balance BIGINT NOT NULL DEFAULT 0,
                      accepts_payments BOOLEAN NOT NULL DEFAULT TRUE,
                      updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      CONSTRAINT chk_accounts_balance CHECK (balance >= 0),
                      INDEX idx_accounts_name (name),
                      INDEX idx_accounts_balance (balance)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """,
                    """
                    CREATE TABLE transactions (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      from_uuid BINARY(16) NULL,
                      to_uuid BINARY(16) NULL,
                      amount BIGINT NOT NULL,
                      type ENUM('PAY','ADMIN','VAULT') NOT NULL,
                      server VARCHAR(32) NOT NULL,
                      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      INDEX idx_tx_from (from_uuid, id),
                      INDEX idx_tx_to (to_uuid, id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """),
            // v2: per-currency balances, history with currency/reason/reverted, and per-player pay blocks.
            (statement, defaultCurrency) -> {
                // The id was validated (a-z, 0-9, _) when config.yml was read, so concatenating it into DDL is safe.
                statement.execute("""
                        CREATE TABLE balances (
                          uuid BINARY(16) NOT NULL,
                          currency VARCHAR(8) NOT NULL,
                          balance BIGINT NOT NULL DEFAULT 0,
                          max_balance BIGINT NULL,
                          PRIMARY KEY (uuid, currency),
                          CONSTRAINT chk_balances_balance CHECK (balance >= 0),
                          INDEX idx_balances_top (currency, balance)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                statement.execute("INSERT INTO balances (uuid, currency, balance) SELECT uuid, '" + defaultCurrency
                        + "', balance FROM accounts");
                statement.execute("ALTER TABLE accounts DROP CONSTRAINT chk_accounts_balance");
                statement.execute("DROP INDEX idx_accounts_balance ON accounts");
                statement.execute("ALTER TABLE accounts DROP COLUMN balance");
                statement.execute("ALTER TABLE transactions "
                        + "ADD COLUMN currency VARCHAR(8) NOT NULL DEFAULT '" + defaultCurrency + "', "
                        + "ADD COLUMN reason VARCHAR(255) NULL, "
                        + "ADD COLUMN reverted BOOLEAN NOT NULL DEFAULT FALSE, "
                        + "ADD INDEX idx_tx_created (currency, created_at)");
                statement.execute("ALTER TABLE transactions ALTER COLUMN currency DROP DEFAULT");
                statement.execute("""
                        CREATE TABLE pay_blocks (
                          owner BINARY(16) NOT NULL,
                          blocked BINARY(16) NOT NULL,
                          PRIMARY KEY (owner, blocked),
                          INDEX idx_pay_blocks_blocked (blocked)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
            });

    private final HikariDataSource dataSource;

    public Database(DatabaseSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("ZyonPay");
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl("jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/" + settings.name());
        config.setUsername(settings.user());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(Math.min(2, settings.poolSize()));
        config.setConnectionTimeout(settings.connectionTimeoutMs());
        this.dataSource = new HikariDataSource(config);
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Current schema version (used to validate backups). */
    public static int schemaVersion() {
        return MIGRATIONS.size();
    }

    /**
     * Creates or upgrades the schema. Fails fast if the database does not answer.
     *
     * @param defaultCurrency currency that receives the balances and history from before currencies existed
     */
    public void migrate(String defaultCurrency) {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)");
            int current = 0;
            try (ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
                if (rs.next()) {
                    current = rs.getInt(1);
                }
            }
            for (int version = current + 1; version <= MIGRATIONS.size(); version++) {
                MIGRATIONS.get(version - 1).run(statement, defaultCurrency);
                statement.executeUpdate("INSERT INTO schema_version (version) VALUES (" + version + ")");
            }
        } catch (SQLException e) {
            throw new StorageException("Could not migrate the database schema", e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
