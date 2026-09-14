package demo;

import com.atomikos.icatch.jta.UserTransactionManager;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import io.github.rrobetti.xafault.XaOperation;
import io.github.rrobetti.xafault.XaScenarioEngine;
import io.github.rrobetti.xafault.junit5.XaFault;
import io.github.rrobetti.xafault.junit5.XaTest;
import io.github.rrobetti.xafault.timeline.TimelineReport;
import jakarta.transaction.RollbackException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.transaction.xa.XAException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The single-JVM half of "fail ledger-db's PREPARE -> presumed abort rolls
 * everything back": when one resource votes NO at prepare, Atomikos rolls
 * back every already-prepared participant synchronously, inside the very
 * same {@code UserTransactionManager.commit()} call -- the caller sees a
 * {@link RollbackException} and, once it returns, nothing from the
 * transaction survives on either resource.
 *
 * <p>This is the same fault as {@link Phase1RollbackFail} (ledger-db throws
 * {@code XAER_RMFAIL} at prepare), but without the crash: it demonstrates
 * the rollback Atomikos performs on its own, in-process, the instant
 * prepare fails -- as opposed to the *recovery-driven* rollback ("presumed
 * abort" proper) that {@link Phase1RollbackFail} / {@link
 * Phase2RollbackRecover} demonstrate across a genuine crash and restart,
 * where account-db's already-prepared branch is deliberately left dangling
 * instead of being rolled back here and then.
 *
 * <p>Uses j-xa-tester's JUnit 5 extension
 * (https://github.com/rrobetti/j-xa-tester): {@code @XaTest} provisions a
 * fresh {@link XaScenarioEngine} per test, and {@code @XaFault} declares the
 * one-shot prepare failure declaratively instead of building an {@code
 * XaRule} by hand. The extension fails the test in teardown if the fault
 * never fires, so "the fault didn't trigger" can't masquerade as a pass.
 */
@XaTest
class FailAtPrepareRollsBackAllTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void configureAtomikos() {
        System.setProperty("com.atomikos.icatch.log_base_dir", tmp.resolve("tmlogs").toString());
        System.setProperty("com.atomikos.icatch.output_dir", tmp.resolve("tmlogs").toString());
        System.setProperty("com.atomikos.icatch.tm_unique_name", "rolls-back-all-test");
        System.setProperty("com.atomikos.icatch.console_log_level", "WARN");
        // Deterministic, sequential 2PC: with threaded (parallel) 2PC,
        // account-db's prepare and the subsequent rollback-of-everyone can
        // race against each other on separate Propagator threads, which
        // intermittently trips Atomikos into a HEUR_HAZARD state that then
        // retries forever on a background thread.
        System.setProperty("com.atomikos.icatch.threaded_2pc", "false");
    }

    @Test
    @XaFault(resourceId = "ledger-db-2", operation = XaOperation.PREPARE, errorCode = XAException.XAER_RMFAIL)
    void prepareFailureOnOneResourceRollsBackTheOtherWithoutAppCodeSeeingIt(XaScenarioEngine engine) throws Exception {
        String accountPath = tmp.resolve("db/account").toString();
        String ledgerPath = tmp.resolve("db/ledger").toString();
        seedSchema(accountPath, ledgerPath);

        // Distinct resource names from FailBeforeCommitSelfHealsTest's
        // "account-db"/"ledger-db": Atomikos registers resources in
        // JVM-wide static state that outlives a single test method, and
        // Surefire runs all test classes in one forked JVM by default, so
        // reusing those names here would collide with the other test class.
        AtomikosDataSourceBean accountDb = Resources.atomikosDataSource("account-db-2", accountPath, engine);
        AtomikosDataSourceBean ledgerDb = Resources.atomikosDataSource("ledger-db-2", ledgerPath, engine);
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

            // account-db votes YES at prepare; the @XaFault rule above makes
            // ledger-db throw XAER_RMFAIL the moment Atomikos calls
            // prepare() on it -- a NO vote. Atomikos rolls back account-db's
            // already-prepared branch right here, inside this same call.
            assertThrows(RollbackException.class, tm::commit);
        } finally {
            tm.close();
        }

        System.out.println(TimelineReport.render(engine.journal().events()));

        assertEquals(1000, readBalance(accountPath), "account-db should have rolled back to its pre-transfer balance");
        assertEquals(0, countLedgerEntries(ledgerPath), "ledger-db should never have committed an entry");
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
