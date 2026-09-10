package demo;

import com.atomikos.icatch.jta.UserTransactionManager;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import io.github.rrobetti.xafault.XaScenarioEngine;
import io.github.rrobetti.xafault.timeline.TimelineReport;
import java.time.Duration;

/**
 * Fresh JVM, same Atomikos log directory and the same resource names as
 * {@link Phase1Fail}. No fault is injected this time -- the outage is over.
 * Simply starting the transaction manager makes it scan its log, find the
 * transaction Phase 1 left in-doubt, and resolve account-db on its own.
 */
public final class Phase2Recover {
    public static void main(String[] args) throws Exception {
        TmConfig.apply();

        System.out.println(Narration.banner("PHASE 2 -- fresh JVM, same TM log dir: watch Atomikos recover"));
        Db.printState("before recovery (leftover from the simulated crash)");

        // No rules added: account-db behaves normally again.
        XaScenarioEngine engine = new XaScenarioEngine();

        AtomikosDataSourceBean accountDb = Resources.atomikosDataSource("account-db", Db.ACCOUNT_PATH, engine);
        AtomikosDataSourceBean ledgerDb = Resources.atomikosDataSource("ledger-db", Db.LEDGER_PATH, engine);
        accountDb.init();
        ledgerDb.init();

        System.out.println();
        System.out.println("Starting the transaction manager -- watch the com.atomikos.* log lines below:"
                + " it is about to scan " + TmConfig.LOG_DIR + ", find the pending transaction, and"
                + " retry commit() on account-db by itself.");
        System.out.println();

        UserTransactionManager tm = new UserTransactionManager();
        tm.setStartupTransactionService(true);
        tm.setForceShutdown(false);
        tm.init(); // <-- recovery runs as part of starting the transaction service

        Db.State result = Db.awaitRecovered(Duration.ofSeconds(20));

        System.out.println();
        System.out.println(TimelineReport.render(engine.journal().events()));
        Db.printState("after recovery");

        tm.close();

        System.out.println();
        if (result.isConsistent()) {
            System.out.println(Narration.banner("Recovered -- Atomikos finished the transfer with no application code involved."));
        } else {
            System.out.println(Narration.banner("Still inconsistent after 20s -- see the timeline above; recovery may need another moment."));
        }
    }
}
