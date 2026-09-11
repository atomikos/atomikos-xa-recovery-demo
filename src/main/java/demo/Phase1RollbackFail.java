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
 * Same "alice pays rent" transfer as {@link Phase1Fail}, but the fault is
 * injected one phase earlier: ledger-db is rigged to throw XAER_RMFAIL the
 * instant Atomikos calls prepare() on it -- i.e. it votes NO. account-db
 * (the first resource enlisted) has already voted YES and is left holding a
 * prepared-but-unresolved branch. Per the XA/JTA spec, a transaction manager
 * never writes a durable commit decision unless and until *every*
 * participant votes YES at prepare, so with ledger-db voting NO, Atomikos
 * never gets there.
 *
 * <p>Left alone, Atomikos would simply roll back account-db's
 * already-prepared branch itself, synchronously, inside this same {@code
 * tm.commit()} call -- so to actually demonstrate that recovery (not
 * in-process cleanup) is what resolves it, the instant the injected failure
 * is recorded this hard-kills the JVM, leaving account-db's branch exactly
 * as in-doubt as a real crash between prepare and rollback would. Run
 * {@link Phase2RollbackRecover} next to watch a fresh process resolve it on
 * startup by rolling it back -- "presumed abort": with no commit decision on
 * record, Atomikos presumes abort for any in-doubt branch it finds.
 */
public final class Phase1RollbackFail {
    public static void main(String[] args) throws Exception {
        RollbackTmConfig.apply();
        RollbackDb.ensureSchemaAndSeed();

        System.out.println(Narration.banner("PHASE 1 -- fail ledger-db's PREPARE, then crash"));
        RollbackDb.printState("before transfer");

        XaScenarioEngine engine = new XaScenarioEngine();
        engine.addRule(new XaRule(
                XaRules.before("ledger-db", XaOperation.PREPARE),
                XaAction.throwException(XAException.XAER_RMFAIL)));

        // The instant the injected failure is recorded, kill the JVM before
        // Atomikos gets a chance to roll back account-db's already-prepared
        // branch on its own.
        engine.addListener(event -> {
            if ("ledger-db".equals(event.resourceId())
                    && event.operation() == XaOperation.PREPARE
                    && event.position() == EventPosition.AFTER_FAILURE) {
                System.out.println();
                System.out.println("ledger-db just voted NO at prepare. Left alone, Atomikos would roll back"
                        + " account-db's already-prepared branch right now, inside this same call -- instead"
                        + " we hard-kill the JVM immediately, leaving that branch genuinely in doubt.");
                System.out.println();
                System.out.println(TimelineReport.render(engine.journal().events()));
                System.out.println();
                System.out.println(Narration.banner("Halting JVM in 1.5s (simulated crash). Run Phase2RollbackRecover next."));
                System.out.flush();
                // Give account-db's own XA log a moment to fsync the
                // "prepared" state it just durably recorded before we pull
                // the rug -- without this, the crash could race ahead of
                // that write and there'd be no dangling branch left behind.
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                Runtime.getRuntime().halt(1);
            }
        });

        AtomikosDataSourceBean accountDb = Resources.atomikosDataSource("account-db", RollbackDb.ACCOUNT_PATH, engine);
        AtomikosDataSourceBean ledgerDb = Resources.atomikosDataSource("ledger-db", RollbackDb.LEDGER_PATH, engine);
        accountDb.init();
        ledgerDb.init();

        UserTransactionManager tm = new UserTransactionManager();
        tm.setStartupTransactionService(true);
        tm.setForceShutdown(false);
        tm.init();

        System.out.println();
        System.out.println("alice pays rent: debit account-db, record the entry in ledger-db,");
        System.out.println("both inside one Atomikos global transaction.");
        tm.begin();
        try (Connection acct = accountDb.getConnection();
             Connection ledger = ledgerDb.getConnection()) {
            try (PreparedStatement ps = acct.prepareStatement(
                    "UPDATE accounts SET balance = balance - ? WHERE id = 'alice'")) {
                ps.setInt(1, RollbackDb.TRANSFER_AMOUNT);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = ledger.prepareStatement(
                    "INSERT INTO ledger_entries (note, amount) VALUES ('rent payment', ?)")) {
                ps.setInt(1, -RollbackDb.TRANSFER_AMOUNT);
                ps.executeUpdate();
            }
        }

        System.out.println();
        System.out.println("Committing... account-db will vote YES at prepare, but ledger-db is rigged to"
                + " throw XAER_RMFAIL the moment Atomikos calls prepare() on it. The listener above will"
                + " halt the JVM before this call gets a chance to return.");
        tm.commit();

        // Only reached if the injected fault didn't fire -- shouldn't happen.
        System.out.println("tm.commit() returned normally without the injected fault firing.");
        RollbackDb.printState("after commit()");
    }
}
