package demo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

/**
 * Plain (non-XA, autocommit) JDBC helpers used only to seed data and to
 * inspect state from outside the distributed transaction -- this is how an
 * operator would look at the two databases while the demo runs.
 */
final class Db {
    private Db() {}

    static final String ACCOUNT_PATH = TmConfig.DATA_DIR.resolve("db/account").toString();
    static final String LEDGER_PATH = TmConfig.DATA_DIR.resolve("db/ledger").toString();

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

    static void reset() throws Exception {
        try (Connection c = DriverManager.getConnection(ACCOUNT_URL);
             Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        try (Connection c = DriverManager.getConnection(LEDGER_URL);
             Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    record State(int aliceBalance, int ledgerEntryCount) {
        boolean isConsistent() {
            return (ledgerEntryCount == 0 && aliceBalance == STARTING_BALANCE)
                    || (ledgerEntryCount == 1 && aliceBalance == STARTING_BALANCE - TRANSFER_AMOUNT);
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
                s.isConsistent() ? "CONSISTENT" : "*** INCONSISTENT (transfer only half-applied) ***");
    }

    /**
     * Polls until the transfer has actually landed on both sides (the
     * fully-committed target state, not just "no half-application yet"), or
     * the timeout elapses. Prints a short progress line on every poll so the
     * wait for Atomikos' recovery thread is visible, not silent.
     */
    static State awaitRecovered(Duration timeout) throws Exception {
        Instant start = Instant.now();
        Instant deadline = start.plus(timeout);
        State last;
        do {
            last = readState();
            boolean recovered = last.aliceBalance() == STARTING_BALANCE - TRANSFER_AMOUNT && last.ledgerEntryCount() == 1;
            System.out.printf("  ...t=%4ds  alice.balance=%d  ledger_entries.count=%d%s%n",
                    Duration.between(start, Instant.now()).toSeconds(), last.aliceBalance(), last.ledgerEntryCount(),
                    recovered ? "  <- recovered!" : "");
            if (recovered) {
                return last;
            }
            Thread.sleep(1000);
        } while (Instant.now().isBefore(deadline));
        return last;
    }
}
