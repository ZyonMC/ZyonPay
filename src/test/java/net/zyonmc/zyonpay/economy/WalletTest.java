package net.zyonmc.zyonpay.economy;

import net.zyonmc.zyonpay.model.WalletRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletTest {

    private static final long MAX = 1_000_000;

    private static Wallet account(long balance) {
        return new Wallet(balance, WalletRow.NO_MAX);
    }

    @Test
    void depositAndWithdrawTrackPendingDelta() {
        Wallet a = account(1000);
        assertTrue(a.tryDeposit(500, MAX));
        assertTrue(a.tryWithdraw(200));
        assertEquals(1300, a.balance());
        assertEquals(300, a.drainPending());
        assertEquals(0, a.drainPending());
    }

    @Test
    void rejectsOverdraftAndNonPositiveAmounts() {
        Wallet a = account(100);
        assertFalse(a.tryWithdraw(101));
        assertFalse(a.tryWithdraw(0));
        assertFalse(a.tryWithdraw(-5));
        assertFalse(a.tryDeposit(0, MAX));
        assertFalse(a.tryDeposit(-5, MAX));
        assertEquals(100, a.balance());
        assertEquals(0, a.drainPending());
    }

    @Test
    void depositCannotExceedMaxBalanceNorOverflow() {
        Wallet a = account(MAX - 10);
        assertFalse(a.tryDeposit(11, MAX));
        assertTrue(a.tryDeposit(10, MAX));
        assertFalse(a.tryDeposit(Long.MAX_VALUE, MAX));
        assertEquals(MAX, a.balance());
    }

    @Test
    void ownCapOverridesTheCurrencyCap() {
        Wallet a = new Wallet(100, 150);
        assertEquals(150, a.max(MAX));
        assertTrue(a.tryDeposit(50, a.max(MAX)));
        assertFalse(a.tryDeposit(1, a.max(MAX)));
        a.setMaxOverride(WalletRow.NO_MAX);
        assertEquals(MAX, a.max(MAX));
        assertTrue(a.tryDeposit(1, a.max(MAX)));
    }

    @Test
    void failedFlushRestoresPendingWithoutLosingMoney() {
        Wallet a = account(0);
        a.tryDeposit(700, MAX);
        long drained = a.drainPending();
        a.tryDeposit(100, MAX);
        a.restorePending(drained);
        assertEquals(800, a.drainPending());
    }

    @Test
    void reloadKeepsUnflushedLocalChanges() {
        Wallet a = account(1000);
        a.tryDeposit(250, MAX);
        a.reload(2000, WalletRow.NO_MAX);
        assertEquals(2250, a.balance());
    }

    @Test
    void concurrentOperationsNeverCreateOrLoseMoney() throws Exception {
        Wallet a = account(50_000);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<long[]>> results = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            results.add(pool.submit(() -> {
                start.await();
                long net = 0;
                for (int i = 0; i < 5_000; i++) {
                    if (i % 2 == 0 && a.tryDeposit(3, MAX)) {
                        net += 3;
                    } else if (a.tryWithdraw(2)) {
                        net -= 2;
                    }
                }
                return new long[]{net};
            }));
        }
        start.countDown();
        long expected = 50_000;
        for (var future : results) {
            expected += future.get()[0];
        }
        pool.shutdown();
        assertEquals(expected, a.balance());
        assertEquals(expected - 50_000, a.drainPending());
    }
}
