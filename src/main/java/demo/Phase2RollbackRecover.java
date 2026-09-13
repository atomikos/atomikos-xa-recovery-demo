package demo;

import com.atomikos.icatch.jta.UserTransactionManager;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import io.github.rrobetti.xafault.XaScenarioEngine;
import io.github.rrobetti.xafault.timeline.TimelineReport;
import java.time.Duration;

/**
 * Fresh JVM, same Atomikos log directory and the same resource names as
 * {@link Phase1RollbackFail}. No fault is injected this time. Simply
 * starting the transaction manager makes it scan its log, find that
 * account-db has a prepared branch belonging to a transaction with no
 * recorded commit decision, and -- "presumed abort" -- roll it back on its
 * own.
 */
public final class Phase2RollbackRecover {
    public static void main(String[] args) throws Exception {
        RollbackTmConfig.apply();

        System.out.println(Narration.banner("PHASE 2 -- fresh JVM, same TM log dir: watch Atomikos presume abort and roll back"));
        RollbackDb.printState("before recovery (leftover from the simulated crash)");

        System.out.println();
        System.out.println("Before starting the transaction manager: a plain write to account-db's alice"
                + " row, with a short lock timeout, to prove the dangling branch from Phase 1 is still"
                + " holding its lock -- even though the balance above already looks untouched, since a"
                + " prepared-but-uncommitted update was never visible to other connections anyway.");
        boolean writableBeforeRecovery = RollbackDb.canWriteToAccount(Duration.ofMillis(200));
        System.out.println("  plain write " + (writableBeforeRecovery
                ? "succeeded -- unexpected, no dangling branch found."
                : "blocked (lock timeout) -- confirms account-db's branch is still prepared and unresolved."));

        // No rules added: both resources behave normally again.
        XaScenarioEngine engine = new XaScenarioEngine();

        AtomikosDataSourceBean accountDb = Resources.atomikosDataSource("account-db", RollbackDb.ACCOUNT_PATH, engine);
        AtomikosDataSourceBean ledgerDb = Resources.atomikosDataSource("ledger-db", RollbackDb.LEDGER_PATH, engine);
        accountDb.init();
        ledgerDb.init();

        System.out.println();
        System.out.println("Starting the transaction manager -- watch the com.atomikos.* log lines below:"
                + " it is about to scan " + RollbackTmConfig.LOG_DIR + ", find account-db's prepared branch"
                + " has no matching commit decision, and roll it back by itself.");
        System.out.println();

        UserTransactionManager tm = new UserTransactionManager();
        tm.setStartupTransactionService(true);
        tm.setForceShutdown(false);
        tm.init(); // <-- recovery runs as part of starting the transaction service

        boolean lockReleased = RollbackDb.awaitLockReleased(Duration.ofSeconds(20));

        System.out.println();
        System.out.println(TimelineReport.render(engine.journal().events()));
        RollbackDb.printState("after recovery");

        tm.close();

        System.out.println();
        if (lockReleased) {
            System.out.println(Narration.banner("Presumed abort -- Atomikos rolled back the dangling branch with no application code involved."));
        } else {
            System.out.println(Narration.banner("Lock still held after 20s -- see the timeline above; recovery may need another moment."));
        }
    }
}
