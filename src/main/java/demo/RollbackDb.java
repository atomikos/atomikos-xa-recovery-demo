package demo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

/**
 * Plain (non-XA, autocommit) JDBC helpers for the presumed-abort scenario --
 * kept separate from {@link Db} so this scenario never shares database files
 * with the fail-before-commit scenario.
 */
final class RollbackDb {
    private RollbackDb() {}

    static final String ACCOUNT_PATH = RollbackTmConfig.DATA_DIR.resolve("db/account").toString();
    static final String LEDGER_PATH = RollbackTmConfig.DATA_DIR.resolve("db/ledger").toString();

    private static final String ACCOUNT_URL = "jdbc:h2:file:" + ACCOUNT_PATH + ";DB_CLOSE_ON_EXIT=FALSE";
    private static final String LEDGER_URL = "jdbc:h2:file:" + LEDGER_PATH + ";DB_CLOSE_ON_EXIT=FALSE";

    static final int STARTING_BALANCE = 1000;
    static final int TRANSFER_AMOUNT = 200;

    static void ensureSchemaAndSeed() throws Exception {
        try (Connection c = DriverManager.getConnection(ACCOUNT_URL);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS accounts (id VARCHAR PRIMARY KEY, balance INT NOT NULL)");
            st.execute("MERGE INTO accounts (id, balance) KEY (id) "
                    + "SELECT 'alice', " + STARTING_BALANCE
                    + " WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE id = 'alice')");
        }
        try (Connection c = DriverManager.getConnection(LEDGER_URL);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS ledger_entries ("
                    + "id IDENTITY PRIMARY KEY, note VARCHAR NOT NULL, amount INT NOT NULL)");
        }
    }

    record State(int aliceBalance, int ledgerEntryCount) {
        boolean isConsistent() {
            // Unlike the fail-before-commit scenario, this transfer has only
            // one correct outcome: ledger-db always votes NO at prepare, so
            // the whole transaction must roll back. "Consistent" here means
            // "as if the transfer never happened".
            return ledgerEntryCount == 0 && aliceBalance == STARTING_BALANCE;
        }
    }

    static State readState() throws Exception {
        int balance;
        try (Connection c = DriverManager.getConnection(ACCOUNT_URL);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT balance FROM accounts WHERE id = 'alice'")) {
            rs.next();
            balance = rs.getInt(1);
        }
        int ledgerCount;
        try (Connection c = DriverManager.getConnection(LEDGER_URL);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ledger_entries")) {
            rs.next();
            ledgerCount = rs.getInt(1);
        }
        return new State(balance, ledgerCount);
    }

    static void printState(String label) throws Exception {
        State s = readState();
        System.out.printf(
                "[%s] account-db: alice.balance=%d  |  ledger-db: ledger_entries.count=%d  |  %s%n",
                label, s.aliceBalance(), s.ledgerEntryCount(),
                s.isConsistent() ? "CONSISTENT" : "*** INCONSISTENT ***");
    }

    /**
     * Tries a plain autocommit write to alice's row with a short lock
     * timeout. account-db's XA prepare holds a write lock on that row for as
     * long as its branch stays prepared-but-unresolved, so this fails (lock
     * timeout) while the dangling transaction is still in doubt, and
     * succeeds the instant Atomikos rolls it back and releases the lock --
     * a functional consequence the balance alone can't show, since a
     * prepared-but-uncommitted update was never visible to other
     * connections in the first place.
     */
    static boolean canWriteToAccount(Duration lockTimeout) throws Exception {
        try (Connection c = DriverManager.getConnection(ACCOUNT_URL);
             Statement st = c.createStatement()) {
            st.execute("SET LOCK_TIMEOUT " + lockTimeout.toMillis());
            st.executeUpdate("UPDATE accounts SET balance = balance WHERE id = 'alice'");
            return true;
        } catch (SQLException lockTimedOut) {
            return false;
        }
    }

    /**
     * Polls until a plain write to account-db's alice row succeeds again --
     * proof that Atomikos rolled back the dangling prepared branch and
     * released its lock -- or the timeout elapses. Prints a short progress
     * line on every poll so the wait for Atomikos' recovery thread is
     * visible, not silent.
     */
    static boolean awaitLockReleased(Duration timeout) throws Exception {
        Instant start = Instant.now();
        Instant deadline = start.plus(timeout);
        boolean released;
        do {
            released = canWriteToAccount(Duration.ofMillis(200));
            System.out.printf("  ...t=%4ds  plain write to account-db.accounts%s%n",
                    Duration.between(start, Instant.now()).toSeconds(),
                    released ? "  <- succeeded, lock released!" : "  blocked (lock still held by the dangling branch)");
            if (released) {
                return true;
            }
            Thread.sleep(1000);
        } while (Instant.now().isBefore(deadline));
        return released;
    }
}
