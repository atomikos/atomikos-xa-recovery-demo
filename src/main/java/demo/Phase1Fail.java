package demo;

import com.atomikos.icatch.jta.UserTransactionManager;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import io.github.rrobetti.xafault.EventPosition;
import io.github.rrobetti.xafault.XaAction;
import io.github.rrobetti.xafault.XaOperation;
import io.github.rrobetti.xafault.XaRule;
import io.github.rrobetti.xafault.XaRules;
import io.github.rrobetti.xafault.XaScenarioEngine;
import io.github.rrobetti.xafault.timeline.TimelineReport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.transaction.xa.XAException;

/**
 * Runs a two-phase-commit money transfer across two H2 databases coordinated
 * by Atomikos. https://github.com/rrobetti/j-xa-tester wraps the account-db
 * resource so a one-shot fault fires the instant Atomikos calls commit() on
 * it -- simulating that resource manager going away for a moment during the
 * commit phase, after every participant already voted to commit at prepare.
 *
 * <p>Left alone, Atomikos would simply retry that commit call a few seconds
 * later on the same thread and quietly heal itself -- so to actually
 * demonstrate crash recovery, the instant the injected failure is recorded
 * this hard-kills the JVM (no clean Atomikos shutdown), leaving the
 * transaction log exactly as in-doubt as a real crash would. Run
 * {@link Phase2Recover} next to watch a fresh process resolve it on startup.
 */
public final class Phase1Fail {
    public static void main(String[] args) throws Exception {
        TmConfig.apply();
        Db.ensureSchemaAndSeed();

        System.out.println(Narration.banner("PHASE 1 -- inject a fail-before-commit fault, then crash"));
        Db.printState("before transfer");

        XaScenarioEngine engine = new XaScenarioEngine();
        engine.addRule(new XaRule(
                XaRules.before("account-db", XaOperation.COMMIT),
                XaAction.throwException(XAException.XAER_RMFAIL)));

        // The instant the injected failure is recorded, kill the JVM before
        // Atomikos gets a chance to retry it on its own.
        engine.addListener(event -> {
            if ("account-db".equals(event.resourceId())
                    && event.operation() == XaOperation.COMMIT
                    && event.position() == EventPosition.AFTER_FAILURE) {
                System.out.println();
                System.out.println("account-db just failed its commit call. Left alone, Atomikos would retry"
                        + " it automatically in a few seconds -- instead we hard-kill the JVM right now,"
                        + " leaving a genuinely in-doubt transaction behind.");
                System.out.println();
                System.out.println(TimelineReport.render(engine.journal().events()));
                System.out.println();
                System.out.println(Narration.banner("Halting JVM in 1.5s (simulated crash). Run Phase2Recover next."));
                System.out.flush();
                // Give Atomikos' own log writer a moment to fsync the
                // "committing" decision it just made before we pull the rug.
                // Without this, the crash can race ahead of that durable
                // write and there'd be nothing left for Phase 2 to recover.
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                Runtime.getRuntime().halt(1);
            }
        });

        AtomikosDataSourceBean accountDb = Resources.atomikosDataSource("account-db", Db.ACCOUNT_PATH, engine);
        AtomikosDataSourceBean ledgerDb = Resources.atomikosDataSource("ledger-db", Db.LEDGER_PATH, engine);
        accountDb.init();
        ledgerDb.init();

        UserTransactionManager tm = new UserTransactionManager();
        tm.setStartupTransactionService(true);
        tm.setForceShutdown(false);
        tm.init();

        System.out.println();
        System.out.println("alice pays rent: debit account-db, record the entry in ledger-db,");
        System.out.println("both inside one Atomikos global transaction.");
        // A short timeout on just this transaction: Atomikos' recovery
        // thread refuses to force-complete a COMMITTING transaction until
        // its own timeout has expired (it doesn't want to race a commit
        // that might still be legitimately in flight elsewhere). Five
        // minutes -- the configured default -- would make Phase2Recover a
        // very long wait, so this one transaction gets five seconds.
        tm.setTransactionTimeout(5);
        tm.begin();
        try (Connection acct = accountDb.getConnection();
             Connection ledger = ledgerDb.getConnection()) {
            try (PreparedStatement ps = acct.prepareStatement(
                    "UPDATE accounts SET balance = balance - ? WHERE id = 'alice'")) {
                ps.setInt(1, Db.TRANSFER_AMOUNT);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = ledger.prepareStatement(
                    "INSERT INTO ledger_entries (note, amount) VALUES ('rent payment', ?)")) {
                ps.setInt(1, -Db.TRANSFER_AMOUNT);
                ps.executeUpdate();
            }
        }

        System.out.println();
        System.out.println("Committing... account-db is rigged to throw XAER_RMFAIL the moment"
                + " Atomikos calls commit() on it. The listener above will halt the JVM before this"
                + " call gets a chance to return.");
        tm.commit();

        // Only reached if the injected fault didn't fire -- shouldn't happen.
        System.out.println("tm.commit() returned normally without the injected fault firing.");
        Db.printState("after commit()");
    }
}
