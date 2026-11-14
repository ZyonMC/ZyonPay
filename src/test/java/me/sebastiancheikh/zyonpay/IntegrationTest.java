package me.sebastiancheikh.zyonpay;

import me.sebastiancheikh.zyonpay.config.Settings;
import me.sebastiancheikh.zyonpay.economy.AccountCache;
import me.sebastiancheikh.zyonpay.economy.EconomyService;
import me.sebastiancheikh.zyonpay.economy.Flusher;
import me.sebastiancheikh.zyonpay.economy.Result;
import me.sebastiancheikh.zyonpay.model.AccountBalance;
import me.sebastiancheikh.zyonpay.model.AccountRow;
import me.sebastiancheikh.zyonpay.model.HistoryRow;
import me.sebastiancheikh.zyonpay.model.Transaction;
import me.sebastiancheikh.zyonpay.model.TxDetail;
import me.sebastiancheikh.zyonpay.model.TxType;
import me.sebastiancheikh.zyonpay.model.WalletRow;
import me.sebastiancheikh.zyonpay.money.Currency;
import me.sebastiancheikh.zyonpay.money.CurrencyRegistry;
import me.sebastiancheikh.zyonpay.storage.AccountRepository;
import me.sebastiancheikh.zyonpay.storage.Backup;
import me.sebastiancheikh.zyonpay.storage.Database;
import me.sebastiancheikh.zyonpay.storage.MaintenanceRepository;
import me.sebastiancheikh.zyonpay.storage.TransactionRepository;
import me.sebastiancheikh.zyonpay.sync.RedisBus;
import me.sebastiancheikh.zyonpay.top.TopEntry;
import me.sebastiancheikh.zyonpay.top.TopService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests against real MariaDB and Redis. They only run with {@code ZYONPAY_IT=1}:
 * <pre>
 * docker run -d --rm -e MARIADB_ROOT_PASSWORD=test -e MARIADB_DATABASE=zyonpay -p 127.0.0.1:33306:3306 mariadb:11
 * docker run -d --rm -p 127.0.0.1:36379:6379 redis:7
 * ZYONPAY_IT=1 ./gradlew test
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "ZYONPAY_IT", matches = "1")
class IntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger("zyonpay-it");
    private static final long START = 100_000; // 1000.00

    private static Settings settings;
    private static CurrencyRegistry currencies;
    private static Currency dollar;
    private static Currency gems;
    private static Database database;
    private static AccountRepository accounts;
    private static TransactionRepository transactions;
    private static MaintenanceRepository maintenance;

    @BeforeAll
    static void connect() throws Exception {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(IntegrationTest.class.getResourceAsStream("/config.yml")));
        yaml.set("database.port", 33306);
        yaml.set("database.user", "root");
        yaml.set("database.password", "test");
        yaml.set("redis.port", 36379);
        yaml.set("currencies.dollar.pay-tax", 0.10);
        settings = Settings.load(yaml);
        currencies = new CurrencyRegistry(settings.currencies(), settings.placeholders().shortUnits());
        dollar = currencies.defaultCurrency();
        gems = currencies.find("gems").orElseThrow();
        database = new Database(settings.database());
        upgradeFromVersionOne();
        database.migrate(dollar.id()); // idempotent
        accounts = new AccountRepository(database);
        transactions = new TransactionRepository(database);
        maintenance = new MaintenanceRepository(database);
    }

    /** Builds a schema-v1 database with data and checks that the v2 migration keeps it, under the default currency. */
    private static void upgradeFromVersionOne() throws Exception {
        try (Connection c = database.connection(); Statement s = c.createStatement()) {
            for (String table : List.of("pay_blocks", "balances", "transactions", "accounts", "schema_version")) {
                s.execute("DROP TABLE IF EXISTS " + table);
            }
            s.execute("CREATE TABLE schema_version (version INT NOT NULL)");
            s.execute("INSERT INTO schema_version VALUES (1)");
            s.execute("""
                    CREATE TABLE accounts (uuid BINARY(16) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL,
                      balance BIGINT NOT NULL DEFAULT 0, accepts_payments BOOLEAN NOT NULL DEFAULT TRUE,
                      updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      CONSTRAINT chk_accounts_balance CHECK (balance >= 0),
                      INDEX idx_accounts_name (name), INDEX idx_accounts_balance (balance))
                    """);
            s.execute("""
                    CREATE TABLE transactions (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, from_uuid BINARY(16) NULL,
                      to_uuid BINARY(16) NULL, amount BIGINT NOT NULL, type ENUM('PAY','ADMIN','VAULT') NOT NULL,
                      server VARCHAR(32) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      INDEX idx_tx_from (from_uuid, id), INDEX idx_tx_to (to_uuid, id))
                    """);
            s.execute("INSERT INTO accounts (uuid, name, balance) VALUES (UNHEX(REPLACE(UUID(), '-', '')), 'Legacy', 4200)");
            s.execute("INSERT INTO transactions (amount, type, server) VALUES (77, 'PAY', 's1')");
        }
        database.migrate(dollar.id());
        try (Connection c = database.connection(); Statement s = c.createStatement()) {
            var balances = s.executeQuery("SELECT currency, balance FROM balances");
            assertTrue(balances.next());
            assertEquals("dollar", balances.getString("currency"));
            assertEquals(4200, balances.getLong("balance"));
            var tx = s.executeQuery("SELECT currency, reverted FROM transactions");
            assertTrue(tx.next());
            assertEquals("dollar", tx.getString("currency"));
            assertFalse(tx.getBoolean("reverted"));
        }
    }

    @AfterAll
    static void close() {
        database.close();
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = database.connection(); Statement s = c.createStatement()) {
            for (String table : List.of("pay_blocks", "balances", "transactions", "accounts")) {
                s.execute("DELETE FROM " + table);
            }
        }
    }

    // ---------------------------------------------------------------- repositories

    @Test
    void loadOrCreateCreatesOneWalletPerCurrencyAndKeepsBalances() {
        UUID id = UUID.randomUUID();
        var first = accounts.loadOrCreate(id, "Alice", currencies.all());
        assertEquals(START, first.wallets().get("dollar").balance());
        assertEquals(0, first.wallets().get("gems").balance());
        accounts.adjust(id, dollar, 500);
        var again = accounts.loadOrCreate(id, "AliceNew", currencies.all());
        assertEquals(START + 500, again.wallets().get("dollar").balance());
        assertEquals("AliceNew", again.name());
        assertTrue(again.acceptsPayments());
    }

    @Test
    void walletIsCreatedLazilyForCurrenciesAddedLater() {
        UUID id = UUID.randomUUID();
        accounts.loadOrCreate(id, "Old", List.of(dollar));
        assertEquals(0, accounts.wallet(id, gems).orElseThrow().balance());
        assertTrue(accounts.wallet(UUID.randomUUID(), gems).isEmpty(), "no account, no wallet");
    }

    @Test
    void adjustIsAtomicBoundedAndPerCurrency() {
        UUID id = UUID.randomUUID();
        accounts.loadOrCreate(id, "Bob", List.of(gems));
        assertTrue(accounts.adjust(id, gems, 500_000_00));
        assertFalse(accounts.adjust(id, gems, 500_000_01), "exceeds the 1,000,000 cap");
        assertTrue(accounts.adjust(id, gems, 500_000_00));
        assertTrue(accounts.adjust(id, gems, -1_000_000_00));
        assertFalse(accounts.adjust(id, gems, -1), "insufficient");
        assertFalse(accounts.adjust(UUID.randomUUID(), gems, 5), "missing account");
        assertEquals(0, accounts.wallet(id, gems).orElseThrow().balance());
        assertEquals(0, accounts.wallet(id, dollar).orElseThrow().balance() - START, "dollars untouched");
    }

    @Test
    void ownCapOverridesCurrencyCapInDatabase() {
        UUID id = UUID.randomUUID();
        accounts.loadOrCreate(id, "Capped", currencies.all());
        accounts.setMaxBalance(id, dollar, START + 100);
        assertTrue(accounts.adjust(id, dollar, 100));
        assertFalse(accounts.adjust(id, dollar, 1));
        assertEquals(START + 100, accounts.wallet(id, dollar).orElseThrow().maxBalance());
        accounts.setMaxBalance(id, dollar, WalletRow.NO_MAX);
        assertTrue(accounts.adjust(id, dollar, 1));
    }

    @Test
    void applyDeltasClampsButNeverLowersABalanceAboveItsCap() {
        UUID low = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        UUID capped = UUID.randomUUID();
        accounts.loadOrCreate(low, "Low", List.of(gems));
        accounts.loadOrCreate(high, "High", List.of(gems));
        accounts.loadOrCreate(capped, "Capped", List.of(gems));
        accounts.adjust(capped, gems, 5_000);
        accounts.setMaxBalance(capped, gems, 1_000); // cap lowered below the current balance
        accounts.applyDeltas(gems, Map.of(low, -500L, high, 999_999_999_99L, capped, -100L));
        assertEquals(0, accounts.wallet(low, gems).orElseThrow().balance());
        assertEquals(gems.maxBalance(), accounts.wallet(high, gems).orElseThrow().balance());
        assertEquals(4_900, accounts.wallet(capped, gems).orElseThrow().balance(), "keeps money above its cap");
    }

    @Test
    void findByNameIgnoresCase() {
        UUID id = UUID.randomUUID();
        accounts.loadOrCreate(id, "CamelCase", List.of(dollar));
        assertEquals(id, accounts.findByName("camelcase").orElseThrow().uuid());
    }

    @Test
    void payBlocksToggleAndList() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        accounts.loadOrCreate(owner, "Owner", List.of(dollar));
        accounts.loadOrCreate(other, "Spammer", List.of(dollar));
        assertFalse(accounts.isBlocked(owner, other));
        assertTrue(accounts.toggleBlocked(owner, other));
        assertTrue(accounts.isBlocked(owner, other));
        assertEquals(List.of("Spammer"), accounts.blockedNames(owner));
        assertTrue(accounts.loadOrCreate(owner, "Owner", List.of(dollar)).blocked().contains(other));
        assertFalse(accounts.toggleBlocked(owner, other));
        assertTrue(accounts.blockedNames(owner).isEmpty());
    }

    @Test
    void transactionsAreStoredQueriedAndRevertMarked() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        accounts.loadOrCreate(a, "Ann", currencies.all());
        accounts.loadOrCreate(b, "Ben", currencies.all());
        Instant now = Instant.now();
        transactions.insertBatch(List.of(
                new Transaction(a, b, 250, TxType.PAY, "s1", now.minusSeconds(30), "dollar", null),
                new Transaction(null, a, 900, TxType.ADMIN, "s1", now, "gems", "welcome gift"),
                new Transaction(b, null, 50, TxType.VAULT, "s2", now, "dollar", "Shop#buy:12")));
        List<HistoryRow> forA = transactions.recent(a, 10);
        assertEquals(2, forA.size());
        assertEquals(900, forA.get(0).amount(), "newest first");
        assertEquals("gems", forA.get(0).currency());
        assertEquals("welcome gift", forA.get(0).reason());
        assertEquals("Ben", forA.get(1).otherName());
        assertNull(forA.get(0).otherName(), "the system has no name");
        assertEquals(2, transactions.recent(b, 10).size());

        assertEquals(1, transactions.between(a, null, now.minusSeconds(10), 10).size(), "after");
        assertEquals(1, transactions.between(a, now.minusSeconds(10), null, 10).size(), "before");

        TxDetail detail = transactions.find(forA.get(1).id()).orElseThrow();
        assertEquals("Ann", detail.fromName());
        assertEquals("Ben", detail.toName());
        assertTrue(transactions.markReverted(detail.id()));
        assertFalse(transactions.markReverted(detail.id()), "only once");
        transactions.unmarkReverted(detail.id());
        assertTrue(transactions.markReverted(detail.id()));
    }

    @Test
    void historyOlderThanTheTtlIsPurgedPerCurrency() {
        UUID a = UUID.randomUUID();
        accounts.loadOrCreate(a, "Ann", currencies.all());
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        transactions.insertBatch(List.of(
                new Transaction(null, a, 1, TxType.ADMIN, "s1", old, "dollar", null),
                new Transaction(null, a, 2, TxType.ADMIN, "s1", old, "gems", null),
                new Transaction(null, a, 3, TxType.ADMIN, "s1", Instant.now(), "dollar", null)));
        assertEquals(1, transactions.purgeOlderThan("dollar", Instant.now().minus(30, ChronoUnit.DAYS)));
        assertEquals(2, transactions.recent(a, 10).size());
    }

    @Test
    void topAndSupplyArePerCurrency() {
        for (int i = 1; i <= 5; i++) {
            UUID id = UUID.randomUUID();
            accounts.loadOrCreate(id, "P" + i, List.of(dollar, gems));
            accounts.adjust(id, gems, i * 100L);
        }
        List<AccountBalance> top = accounts.top("gems", 3);
        assertEquals(List.of("P5", "P4", "P3"), top.stream().map(AccountBalance::name).toList());
        assertEquals(1_500, accounts.supply("gems"));
        assertEquals(5 * START, accounts.supply("dollar"));
        assertEquals(0, accounts.supply("nothing"));
    }

    // ---------------------------------------------------------------- maintenance

    @Test
    void purgeDeletesMatchingAccountsOnly() {
        UUID keep = UUID.randomUUID();
        UUID drop = UUID.randomUUID();
        accounts.loadOrCreate(keep, "RealPlayer", currencies.all());
        accounts.loadOrCreate(drop, "bot_123", currencies.all());
        List<AccountRow> matches = maintenance.accountsMatching(Pattern.compile("bot_\\d+"));
        assertEquals(List.of(drop), matches.stream().map(AccountRow::uuid).toList());
        assertEquals(1, maintenance.deleteAccounts(List.of(drop)));
        assertTrue(accounts.findByUuid(drop).isEmpty());
        assertTrue(accounts.wallet(drop, dollar).isEmpty());
        assertTrue(accounts.findByUuid(keep).isPresent());
    }

    @Test
    void importAccountSkipsExistingAccounts() {
        UUID id = UUID.randomUUID();
        assertTrue(maintenance.importAccount(id, "Imported", dollar, 12_345));
        assertEquals(12_345, accounts.wallet(id, dollar).orElseThrow().balance());
        assertFalse(maintenance.importAccount(id, "Imported", dollar, 1));
        assertEquals(12_345, accounts.wallet(id, dollar).orElseThrow().balance());
    }

    @Test
    void swapCurrenciesExchangesBalancesAndHistory() {
        UUID id = UUID.randomUUID();
        accounts.loadOrCreate(id, "Swap", currencies.all());
        accounts.adjust(id, gems, 700);
        transactions.insertBatch(List.of(new Transaction(null, id, 5, TxType.ADMIN, "s1", Instant.now(), "gems", null)));
        maintenance.swapCurrencies("dollar", "gems");
        assertEquals(700, accounts.wallet(id, dollar).orElseThrow().balance());
        assertEquals(START, accounts.wallet(id, gems).orElseThrow().balance());
        assertEquals("dollar", transactions.recent(id, 1).get(0).currency());
    }

    @Test
    void backupAndRestoreRoundTrip(@TempDir Path dir) {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        accounts.loadOrCreate(a, "Ann", currencies.all());
        accounts.loadOrCreate(b, "Ben", currencies.all());
        accounts.setMaxBalance(a, gems, 999);
        accounts.toggleBlocked(a, b);
        transactions.insertBatch(List.of(
                new Transaction(a, b, 250, TxType.PAY, "s1", Instant.now(), "dollar", "gift"),
                new Transaction(null, a, 9, TxType.ADMIN, "s1", Instant.now(), "gems", null)));
        Path file = dir.resolve("backup.json.gz");
        Backup backup = new Backup(database);
        backup.write(file);

        maintenance.deleteAccounts(List.of(a, b));
        transactions.purgeOlderThan("dollar", Instant.now().plusSeconds(60));
        transactions.purgeOlderThan("gems", Instant.now().plusSeconds(60));
        assertTrue(accounts.findByUuid(a).isEmpty());

        backup.restore(file);
        assertEquals("Ann", accounts.findByUuid(a).orElseThrow().name());
        assertEquals(999, accounts.wallet(a, gems).orElseThrow().maxBalance());
        assertEquals(START, accounts.wallet(b, dollar).orElseThrow().balance());
        assertTrue(accounts.isBlocked(a, b));
        List<HistoryRow> history = transactions.recent(a, 10);
        assertEquals(2, history.size());
        assertEquals("gift", history.get(1).reason());
    }

    // ---------------------------------------------------------------- economy service

    private record Setup(EconomyService economy, ScheduledExecutorService storage) {
    }

    private static Setup economy() {
        ScheduledExecutorService storage = new ScheduledThreadPoolExecutor(1);
        RedisBus bus = new RedisBus(redisDisabled(), "s1", false, LOG);
        Flusher flusher = new Flusher(accounts, transactions, currencies, LOG);
        return new Setup(new EconomyService(settings, currencies, new AccountCache(), accounts, transactions, flusher,
                bus, storage, LOG), storage);
    }

    @Test
    void economyFlowAcrossCachedAndOfflineAccountsWithTax() throws Exception {
        Setup setup = economy();
        EconomyService economy = setup.economy();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        economy.load(alice, "Alice");
        economy.load(bob, "Bob");
        accounts.loadOrCreate(offline, "Off", currencies.all());

        assertEquals(Result.SUCCESS, economy.pay(dollar, alice, "Alice", bob, 30_000));
        assertEquals(Result.SUCCESS, economy.pay(dollar, alice, "Alice", offline, 20_000), "offline pay goes straight to the DB");
        assertEquals(Result.INSUFFICIENT_FUNDS, economy.pay(dollar, alice, "Alice", bob, 1_000_000));
        assertEquals(Result.SELF, economy.pay(dollar, alice, "Alice", alice, 1));
        assertEquals(Result.NOT_FOUND, economy.pay(dollar, alice, "Alice", UUID.randomUUID(), 1));
        assertEquals(Result.SUCCESS, economy.withdraw(dollar, offline, 5_000, TxType.VAULT, null, "Shop#buy:1"));
        assertEquals(Result.INSUFFICIENT_FUNDS, economy.withdraw(dollar, offline, 999_999_999, TxType.VAULT, null, null));

        // 10% tax: the sender pays the full amount, the recipient gets 90%.
        assertEquals(START - 50_000, economy.cachedBalance(alice, dollar).orElseThrow());
        assertEquals(START + 27_000, economy.cachedBalance(bob, dollar).orElseThrow());
        assertEquals(START, accounts.wallet(alice, dollar).orElseThrow().balance(), "not flushed yet");

        setup.storage().submit(economy::flushAll).get();
        assertEquals(START - 50_000, accounts.wallet(alice, dollar).orElseThrow().balance());
        assertEquals(START + 27_000, accounts.wallet(bob, dollar).orElseThrow().balance());
        assertEquals(START + 18_000 - 5_000, accounts.wallet(offline, dollar).orElseThrow().balance());
        List<HistoryRow> aliceHistory = transactions.recent(alice, 10);
        assertEquals(4, aliceHistory.size(), "two payments plus their two tax records");
        assertTrue(aliceHistory.stream().anyMatch(row -> "tax".equals(row.reason())));

        assertEquals(0, economy.cachedBalance(alice, gems).orElseThrow(), "gems are a separate wallet");
        assertEquals(Result.INSUFFICIENT_FUNDS, economy.pay(gems, alice, "Alice", bob, 100));

        economy.toggleAcceptsPayments(bob);
        assertEquals(Result.PAYMENTS_DISABLED, economy.pay(dollar, alice, "Alice", bob, 100));
        economy.toggleAcceptsPayments(bob);

        assertTrue(economy.toggleBlocked(bob, alice));
        assertEquals(Result.BLOCKED, economy.pay(dollar, alice, "Alice", bob, 100));
        assertTrue(economy.toggleBlocked(offline, alice));
        assertEquals(Result.BLOCKED, economy.pay(dollar, alice, "Alice", offline, 100), "offline recipients block too");
        assertFalse(economy.toggleBlocked(bob, alice));
        assertEquals(Result.SUCCESS, economy.pay(dollar, alice, "Alice", bob, 100));

        economy.unload(alice);
        setup.storage().shutdown();
        setup.storage().awaitTermination(5, TimeUnit.SECONDS);
        assertFalse(economy.isCached(alice));
    }

    @Test
    void ownCapIsEnforcedOnCachedAccounts() throws Exception {
        Setup setup = economy();
        EconomyService economy = setup.economy();
        UUID id = UUID.randomUUID();
        economy.load(id, "Capped");
        assertEquals(Result.SUCCESS, economy.setMaxBalance(dollar, id, START + 10));
        assertEquals(START + 10, economy.maxBalance(id, dollar));
        assertEquals(Result.SUCCESS, economy.deposit(dollar, id, 10, TxType.ADMIN, null, null));
        assertEquals(Result.MAX_BALANCE, economy.deposit(dollar, id, 1, TxType.ADMIN, null, null));
        assertEquals(Result.SUCCESS, economy.setMaxBalance(dollar, id, WalletRow.NO_MAX));
        assertEquals(dollar.maxBalance(), economy.maxBalance(id, dollar));
        assertEquals(Result.NOT_FOUND, economy.setMaxBalance(dollar, UUID.randomUUID(), 5));
        setup.storage().shutdownNow();
    }

    @Test
    void revertReturnsTheMoneyOnceAndRecordsIt() throws Exception {
        Setup setup = economy();
        EconomyService economy = setup.economy();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        economy.load(alice, "Alice");
        accounts.loadOrCreate(bob, "Bob", currencies.all());
        transactions.insertBatch(List.of(new Transaction(alice, bob, 1_000, TxType.PAY, "s1", Instant.now(), "dollar", null)));
        TxDetail tx = transactions.recent(alice, 1).stream().map(row -> transactions.find(row.id()).orElseThrow()).findFirst().orElseThrow();

        assertEquals(Result.SUCCESS, economy.revert(tx));
        assertEquals(START - 1_000, accounts.wallet(bob, dollar).orElseThrow().balance());
        assertEquals(START + 1_000, economy.cachedBalance(alice, dollar).orElseThrow());
        assertEquals(Result.ALREADY_REVERTED, economy.revert(tx));

        setup.storage().submit(economy::flushAll).get();
        List<HistoryRow> history = transactions.recent(alice, 10);
        assertEquals(2, history.size());
        assertEquals("revert #" + tx.id(), history.get(0).reason());
        assertTrue(transactions.find(tx.id()).orElseThrow().reverted());

        // Bob does not have that much, so the second transfer cannot be reverted and stays revertible.
        transactions.insertBatch(List.of(new Transaction(alice, bob, 10_000_000, TxType.PAY, "s1", Instant.now(), "dollar", null)));
        TxDetail second = transactions.recent(alice, 1).stream().map(row -> transactions.find(row.id()).orElseThrow()).findFirst().orElseThrow();
        assertEquals(Result.INSUFFICIENT_FUNDS, economy.revert(second));
        assertFalse(transactions.find(second.id()).orElseThrow().reverted());
        setup.storage().shutdownNow();
    }

    // ---------------------------------------------------------------- redis

    @Test
    void topSharedThroughRedisWithSingleLeader() {
        for (int i = 1; i <= 4; i++) {
            UUID id = UUID.randomUUID();
            accounts.loadOrCreate(id, "R" + i, List.of(dollar, gems));
            accounts.adjust(id, gems, i * 100L);
        }
        Settings.RedisSettings redis = settings.redis();
        RedisBus bus1 = new RedisBus(redis, "s1", false, LOG);
        RedisBus bus2 = new RedisBus(redis, "s2", false, LOG);
        try {
            bus1.execute(commands -> commands.flushall());
            TopService top1 = new TopService(10, 30, "s1", accounts, bus1, currencies, LOG);
            TopService top2 = new TopService(10, 30, "s2", accounts, bus2, currencies, LOG);
            top1.refresh(); // wins the lock and rebuilds
            top2.refresh(); // not the leader: reads the shared ZSETs
            List<TopEntry> second = top2.snapshot("gems");
            assertEquals(List.of("R4", "R3", "R2", "R1"), second.stream().map(TopEntry::name).toList());
            assertEquals(1, second.get(0).rank());
            assertEquals(400, second.get(0).balance());
            assertEquals(second, top1.snapshot("gems"));
            assertEquals(4 * START, top2.supply("dollar"));
            assertEquals(1_000, top2.supply("gems"));
        } finally {
            bus1.close();
            bus2.close();
        }
    }

    @Test
    void pubSubDeliversToOtherServersOnly() throws Exception {
        Settings.RedisSettings redis = settings.redis();
        RedisBus sender = new RedisBus(redis, "s1", false, LOG);
        RedisBus receiver = new RedisBus(redis, "s2", false, LOG);
        List<String> received = new CopyOnWriteArrayList<>();
        RedisBus.Listener listener = new RedisBus.Listener() {
            @Override
            public void onRefresh(UUID uuid, String currency) {
                received.add("refresh:" + uuid + ":" + currency);
            }

            @Override
            public void onPaid(UUID to, String fromName, long cents, String currency) {
                received.add("paid:" + to + ":" + fromName + ":" + cents + ":" + currency);
            }

            @Override
            public void onResync() {
                received.add("resync");
            }
        };
        try {
            sender.start(listener);
            receiver.start(listener);
            Thread.sleep(500); // let both subscriptions settle
            UUID id = UUID.randomUUID();
            sender.publishRefresh(id, "gems");
            sender.publishPaid(id, "Alice", 1_250, "dollar");
            sender.publishResync();
            long deadline = System.currentTimeMillis() + 5_000;
            while (received.size() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            Thread.sleep(300); // a message looped back to the sender would show up by now
            assertEquals(List.of("refresh:" + id + ":gems", "paid:" + id + ":Alice:1250:dollar", "resync"), received);
        } finally {
            sender.close();
            receiver.close();
        }
    }

    private static Settings.RedisSettings redisDisabled() {
        Settings.RedisSettings r = settings.redis();
        return new Settings.RedisSettings(false, r.host(), r.port(), r.user(), r.password(), r.database(), r.prefix(),
                r.timeoutMs(), r.ssl());
    }
}
