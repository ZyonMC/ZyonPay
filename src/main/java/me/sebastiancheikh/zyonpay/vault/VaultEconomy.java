package me.sebastiancheikh.zyonpay.vault;

import me.sebastiancheikh.zyonpay.config.Settings;
import me.sebastiancheikh.zyonpay.economy.EconomyService;
import me.sebastiancheikh.zyonpay.economy.Result;
import me.sebastiancheikh.zyonpay.model.AccountRow;
import me.sebastiancheikh.zyonpay.model.TxType;
import me.sebastiancheikh.zyonpay.money.Currency;
import me.sebastiancheikh.zyonpay.money.CurrencyRegistry;
import me.sebastiancheikh.zyonpay.money.Money;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Vault facade for one currency. Calls are synchronous: players connected here hit the cache only; uncached
 * accounts (offline, or online on another server) block the calling thread on a MariaDB query.
 * Worlds and banks are not supported: the economy is global.
 */
public final class VaultEconomy implements Economy {

    private static final EconomyResponse NO_BANKS =
            new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "ZyonPay does not support banks");

    private final EconomyService economy;
    private final CurrencyRegistry currencies;
    private final String currencyId;
    private final Settings settings;

    public VaultEconomy(EconomyService economy, CurrencyRegistry currencies, String currencyId, Settings settings) {
        this.economy = economy;
        this.currencies = currencies;
        this.currencyId = currencyId;
        this.settings = settings;
    }

    /** Resolved on every call because a config reload replaces the currency objects. */
    private Currency currency() {
        return currencies.find(currencyId).orElseGet(currencies::defaultCurrency);
    }

    // ------------------------------------------------------------ info

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "ZyonPay";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return Money.SCALE;
    }

    @Override
    public String format(double amount) {
        OptionalLong cents = Money.fromDouble(amount);
        return cents.isPresent() ? currency().format(cents.getAsLong()) : String.valueOf(amount);
    }

    @Override
    public String currencyNamePlural() {
        return currency().plural();
    }

    @Override
    public String currencyNameSingular() {
        return currency().singular();
    }

    // ------------------------------------------------------------ accounts

    @Override
    public boolean hasAccount(String playerName) {
        return resolve(playerName).isPresent();
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return economy.exists(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return resolve(playerName).isPresent();
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        if (economy.exists(player.getUniqueId())) {
            return true;
        }
        return player.getName() != null && economy.ensureAccount(player.getUniqueId(), player.getName());
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // ------------------------------------------------------------ balance

    @Override
    public double getBalance(String playerName) {
        return resolve(playerName).map(row -> balanceOf(row.uuid())).orElse(0.0);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return balanceOf(player.getUniqueId());
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return resolve(playerName).map(row -> hasEnough(balanceCents(row.uuid()), amount)).orElse(false);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return hasEnough(balanceCents(player.getUniqueId()), amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    // ------------------------------------------------------------ withdrawals and deposits

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return resolve(playerName)
                .map(row -> transact(row.uuid(), amount, false))
                .orElseGet(() -> failure(amount, 0, "Account not found"));
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return transact(player.getUniqueId(), amount, false);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return resolve(playerName)
                .map(row -> transact(row.uuid(), amount, true))
                .orElseGet(() -> failure(amount, 0, "Account not found"));
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return transact(player.getUniqueId(), amount, true);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    // ------------------------------------------------------------ banks (unsupported)

    @Override
    public EconomyResponse createBank(String name, String player) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return NO_BANKS;
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    // ------------------------------------------------------------ internals

    private Optional<AccountRow> resolve(String playerName) {
        return economy.findByName(playerName);
    }

    private long balanceCents(UUID uuid) {
        return economy.balance(uuid, currency()).orElse(0);
    }

    private double balanceOf(UUID uuid) {
        return Money.toDouble(balanceCents(uuid));
    }

    private static boolean hasEnough(long balanceCents, double amount) {
        OptionalLong needed = Money.fromDouble(amount);
        return needed.isPresent() && balanceCents >= needed.getAsLong();
    }

    private EconomyResponse transact(UUID uuid, double amount, boolean deposit) {
        OptionalLong cents = Money.fromDouble(amount);
        if (cents.isEmpty()) {
            return failure(amount, balanceOf(uuid), "Invalid amount");
        }
        Currency currency = currency();
        String reason = CallerTracker.capture(settings.registerCallsVerbosity(), settings.callBlacklist());
        Result result;
        if (deposit) {
            // With tax-only-pay off, the pay tax also applies to money deposited by other plugins.
            long credited = currency.taxOnlyPay() ? cents.getAsLong() : cents.getAsLong() - currency.tax(cents.getAsLong());
            result = economy.deposit(currency, uuid, credited, TxType.VAULT, null, reason);
        } else {
            result = economy.withdraw(currency, uuid, cents.getAsLong(), TxType.VAULT, null, reason);
        }
        double balance = balanceOf(uuid);
        return switch (result) {
            case SUCCESS -> new EconomyResponse(amount, balance, ResponseType.SUCCESS, null);
            case INSUFFICIENT_FUNDS -> failure(amount, balance, "Insufficient funds");
            case MAX_BALANCE -> failure(amount, balance, "Maximum balance exceeded");
            case NOT_FOUND -> failure(amount, balance, "Account not found");
            default -> failure(amount, balance, "Internal economy error");
        };
    }

    private static EconomyResponse failure(double amount, double balance, String message) {
        return new EconomyResponse(amount, balance, ResponseType.FAILURE, message);
    }
}
