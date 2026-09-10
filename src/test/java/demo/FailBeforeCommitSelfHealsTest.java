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
import javax.transaction.xa.XAException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The half of "fail-before-commit -> watch Atomikos recover" that fits
 * inside a single JVM: left alone (no crash), Atomikos retries a failed
 * commit call on its own -- inside the very same {@code
 * UserTransactionManager.commit()} invocation -- and self-heals. Discovered
 * while building {@link Phase1Fail}: a plain {@code tm.commit()} call took
 * about ten seconds and returned normally, because Atomikos retried
 * account-db's commit once its {@code oltp_retry_interval} elapsed.
 *
 * <p>The other half of the demo -- a genuine crash and a second JVM reading
 * the leftover transaction log ({@link Phase1Fail} / {@link Phase2Recover})
 * -- can't be expressed as a JUnit test: it requires {@code
 * Runtime.halt()}ing the process a test is running in.
 *
 * <p>Uses j-xa-tester's JUnit 5 extension
 * (https://github.com/rrobetti/j-xa-tester): {@code @XaTest} provisions a
 * fresh {@link XaScenarioEngine} per test, and {@code @XaFault} declares the
 * one-shot commit failure declaratively instead of building an {@code
 * XaRule} by hand. The extension fails the test in teardown if the fault
 * never fires, so "the fault didn't trigger" can't masquerade as a pass.
 */
@XaTest
class FailBeforeCommitSelfHealsTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void configureAtomikos() {
        System.setProperty("com.atomikos.icatch.log_base_dir", tmp.resolve("tmlogs").toString());
        System.setProperty("com.atomikos.icatch.output_dir", tmp.resolve("tmlogs").toString());
        System.setProperty("com.atomikos.icatch.tm_unique_name", "self-heals-test");
        System.setProperty("com.atomikos.icatch.console_log_level", "WARN");
        // Default retry interval is 10s; shortened so the test doesn't
        // spend real wall-clock time waiting on it.
        System.setProperty("com.atomikos.icatch.oltp_retry_interval", "300");
        System.setProperty("com.atomikos.icatch.oltp_max_retries", "3");
    }

    @Test
    @XaFault(resourceId = "account-db", operation = XaOperation.COMMIT, errorCode = XAException.XAER_RMFAIL)
    void transientCommitFailureIsRetriedAndSelfHealedWithoutAppCodeSeeingIt(XaScenarioEngine engine) throws Exception {
        String accountPath = tmp.resolve("db/account").toString();
        String ledgerPath = tmp.resolve("db/ledger").toString();
        seedSchema(accountPath, ledgerPath);

        AtomikosDataSourceBean accountDb = Resources.atomikosDataSource("account-db", accountPath, engine);
        AtomikosDataSourceBean ledgerDb = Resources.atomikosDataSource("ledger-db", ledgerPath, engine);
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

            // The @XaFault rule above throws XAER_RMFAIL the first time
            // Atomikos calls commit() on account-db. With no crash to stop
            // it, Atomikos retries that same call a moment later and this
            // returns normally -- the caller never sees the failure.
            tm.commit();
        } finally {
            tm.close();
        }

        System.out.println(TimelineReport.render(engine.journal().events()));

        assertEquals(800, readBalance(accountPath), "account-db should have healed to the post-transfer balance");
        assertEquals(1, countLedgerEntries(ledgerPath), "ledger-db should have committed its entry");
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
