package net.zyonmc.zyonpay.economy;

import net.zyonmc.zyonpay.model.Transaction;
import net.zyonmc.zyonpay.money.Currency;
import net.zyonmc.zyonpay.money.CurrencyRegistry;
import net.zyonmc.zyonpay.storage.AccountRepository;
import net.zyonmc.zyonpay.storage.StorageException;
import net.zyonmc.zyonpay.storage.TransactionRepository;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Writes pending balance deltas and queued transactions to MariaDB. Always run it on the storage thread:
 * that serializes flush, refresh and unload.
 */
public final class Flusher {

    private record Drained(Wallet wallet, UUID uuid, long delta) {
    }

    // ponytail: unbounded queue; if MariaDB stays down for hours it grows. Add a cap if observed.
    private final Queue<Transaction> transactions = new ConcurrentLinkedQueue<>();
    private final AccountRepository accounts;
    private final TransactionRepository transactionRepository;
    private final CurrencyRegistry currencies;
    private final Logger log;

    public Flusher(AccountRepository accounts, TransactionRepository transactionRepository,
                   CurrencyRegistry currencies, Logger log) {
        this.accounts = accounts;
        this.transactionRepository = transactionRepository;
        this.currencies = currencies;
        this.log = log;
    }

    public void log(Transaction transaction) {
        transactions.add(transaction);
    }

    public void flush(Collection<Account> targets) {
        flushBalances(targets);
        flushTransactions();
    }

    private void flushBalances(Collection<Account> targets) {
        Map<String, List<Drained>> byCurrency = new HashMap<>();
        for (Account account : targets) {
            account.wallets().forEach((currency, wallet) -> {
                long delta = wallet.drainPending();
                if (delta != 0) {
                    byCurrency.computeIfAbsent(currency, key -> new ArrayList<>())
                            .add(new Drained(wallet, account.uuid(), delta));
                }
            });
        }
        byCurrency.forEach(this::write);
    }

    private void write(String currencyId, List<Drained> drained) {
        Optional<Currency> currency = currencies.find(currencyId);
        if (currency.isEmpty()) {
            log.warn("Dropping {} pending balance changes for currency '{}': it is no longer configured",
                    drained.size(), currencyId);
            return;
        }
        Map<UUID, Long> deltas = new HashMap<>();
        drained.forEach(d -> deltas.put(d.uuid(), d.delta()));
        try {
            accounts.applyDeltas(currency.get(), deltas);
        } catch (StorageException e) {
            drained.forEach(d -> d.wallet().restorePending(d.delta()));
            log.warn("Could not flush {} {} balances; will retry next cycle", drained.size(), currencyId, e);
        }
    }

    private void flushTransactions() {
        List<Transaction> batch = new ArrayList<>();
        Transaction next;
        while ((next = transactions.poll()) != null) {
            batch.add(next);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            transactionRepository.insertBatch(batch);
        } catch (StorageException e) {
            transactions.addAll(batch);
            log.warn("Could not save {} transactions; will retry next cycle", batch.size(), e);
        }
    }
}
