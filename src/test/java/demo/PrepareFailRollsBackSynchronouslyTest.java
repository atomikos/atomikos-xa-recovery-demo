package demo;

import com.atomikos.icatch.jta.UserTransactionManager;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import io.github.rrobetti.xafault.XaOperation;
import io.github.rrobetti.xafault.XaScenarioEngine;
import io.github.rrobetti.xafault.junit5.XaFault;
import io.github.rrobetti.xafault.junit5.XaTest;
import io.github.rrobetti.xafault.timeline.TimelineReport;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import jakarta.transaction.RollbackException;
import javax.transaction.xa.XAException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The single-JVM half of the presumed-abort scenario, symmetric to
 * {@link FailBeforeCommitSelfHealsTest}. The crash+restart pair
 * ({@link Phase1RollbackFail} / {@link Phase2RollbackRecover}) cannot be a
 * JUnit test -- it needs {@code Runtime.halt()} and a second JVM -- but the
 * no-crash path can: ledger-db votes NO at prepare, so -- left alone --
 * Atomikos rolls account-db's already-prepared branch back synchronously
 * inside the same {@code tm.commit()} call and the caller sees a
 * {@link RollbackException}. Nothing is left half-applied.
 *
 * <p>Uses j-xa-tester's JUnit 5 extension: {@code @XaFault} declares the
 * one-shot prepare failure, and the extension fails the test if it never
 * fires.
 */
@XaTest
class PrepareFailRollsBackSynchronouslyTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void configureAtomikos() {
        System.setProperty("com.atomikos.icatch.log_base_dir", tmp.resolve("tmlogs").toString());
        System.setProperty("com.atomikos.icatch.output_dir", tmp.resolve("tmlogs").toString());
        System.setProperty("com.atomikos.icatch.tm_unique_name", "prepare-fail-rollback-test");
        System.setProperty("com.atomikos.icatch.console_log_level", "WARN");
        // Sequential 2PC so account-db (used first) prepares -- and votes YES --
        // before ledger-db's prepare is called and fails.
        System.setProperty("com.atomikos.icatch.threaded_2pc", "false");
    }

    @Test
    @XaFault(resourceId = "rollback-ledger-db", operation = XaOperation.PREPARE, errorCode = XAException.XAER_RMFAIL)
    void prepareVoteNoRollsBackSynchronouslyAndCallerSeesRollbackException(XaScenarioEngine engine) throws Exception {
        String accountPath = tmp.resolve("db/account").toString();
        String ledgerPath = tmp.resolve("db/ledger").toString();
        seedSchema(accountPath, ledgerPath);

        // Distinct resource names from FailBeforeCommitSelfHealsTest: Atomikos
        // registers each uniqueResourceName process-wide, so reusing a name
        // across tests in the same JVM would fail the second init.
        AtomikosDataSourceBean accountDb = Resources.atomikosDataSource("rollback-account-db", accountPath, engine);
        AtomikosDataSourceBean ledgerDb = Resources.atomikosDataSource("rollback-ledger-db", ledgerPath, engine);
        accountDb.init();
        ledgerDb.init();

        UserTransactionManager tm = new UserTransactionManager();
        tm.setStartupTransactionService(true);
        tm.init();
        try {
            tm.begin();
            try (Connection acct = accountDb.getConnection();
                 Connection ledger = ledgerDb.getConnection()) {
                try (PreparedStatement ps = acct.prepareStatement(
                        "UPDATE accounts SET balance = balance - 200 WHERE id = 'alice'")) {
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = ledger.prepareStatement(
                        "INSERT INTO ledger_entries (note, amount) VALUES ('rent payment', -200)")) {
                    ps.executeUpdate();
                }
            }

            // ledger-db votes NO at prepare (the @XaFault above). Not every
            // participant voted YES, so Atomikos rolls the whole transaction
            // back synchronously inside this call -- the caller sees it.
            assertThrows(RollbackException.class, tm::commit);
        } finally {
            tm.close();
        }

        System.out.println(TimelineReport.render(engine.journal().events()));

        // Atomicity: nothing half-applied -- account-db's prepared branch was
        // rolled back and ledger-db never committed.
        assertEquals(1000, readBalance(accountPath), "account-db should be rolled back to its pre-transfer balance");
        assertEquals(0, countLedgerEntries(ledgerPath), "ledger-db should have no committed entry");
    }

    private static void seedSchema(String accountPath, String ledgerPath) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + accountPath);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE accounts (id VARCHAR PRIMARY KEY, balance INT NOT NULL)");
            st.execute("INSERT INTO accounts VALUES ('alice', 1000)");
        }
        try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + ledgerPath);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE ledger_entries (id IDENTITY PRIMARY KEY, note VARCHAR NOT NULL, amount INT NOT NULL)");
        }
    }

    private static int readBalance(String accountPath) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + accountPath);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT balance FROM accounts WHERE id = 'alice'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int countLedgerEntries(String ledgerPath) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + ledgerPath);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ledger_entries")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
