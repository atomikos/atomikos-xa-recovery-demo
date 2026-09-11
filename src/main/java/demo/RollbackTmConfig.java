package demo;

import java.nio.file.Path;

/**
 * Atomikos configuration shared by both phases of the presumed-abort
 * scenario ({@link Phase1RollbackFail} / {@link Phase2RollbackRecover}).
 * Kept separate from {@link TmConfig} -- its own data directory and {@code
 * tm_unique_name} -- so this scenario never shares a transaction log or
 * database files with the fail-before-commit scenario and either can be run
 * (or reset) independently of the other.
 */
final class RollbackTmConfig {
    private RollbackTmConfig() {}

    static final Path DATA_DIR = Path.of("data-rollback").toAbsolutePath();
    static final Path LOG_DIR = DATA_DIR.resolve("tmlogs");

    static void apply() {
        System.setProperty("com.atomikos.icatch.log_base_dir", LOG_DIR.toString());
        System.setProperty("com.atomikos.icatch.output_dir", LOG_DIR.toString());
        System.setProperty("com.atomikos.icatch.log_base_name", "tmlog");
        System.setProperty("com.atomikos.icatch.tm_unique_name", "j-xa-tester-atomikos-demo-rollback");
        System.setProperty("com.atomikos.icatch.enable_logging", "true");
        System.setProperty("com.atomikos.icatch.console_log_level", "INFO");
        // Deterministic, sequential 2PC so account-db (enlisted first) is
        // always the one left holding a prepared-but-unresolved branch when
        // ledger-db (enlisted second) fails its own prepare call.
        System.setProperty("com.atomikos.icatch.threaded_2pc", "false");
        System.setProperty("com.atomikos.icatch.default_jta_timeout", "5000");
        // account-db's dangling prepared branch has no matching commit
        // decision anywhere in Atomikos' own log (the coordinator never even
        // reached the recoverable IN_DOUBT state, since ledger-db failed
        // prepare before every participant had voted). Atomikos treats such
        // an unrecognized xid as "presumed abort", but only rolls it back
        // once max_timeout has passed since first spotting it. Default is 5
        // minutes; shortened here so Phase2RollbackRecover's rollback is
        // prompt to watch.
        System.setProperty("com.atomikos.icatch.max_timeout", "5000");
        // How often Atomikos' background thread rescans the log and
        // resources for in-doubt/unrecognized branches. Default is 5
        // minutes; shortened here for the same reason.
        System.setProperty("com.atomikos.icatch.recovery_delay", "2000");
        LOG_DIR.toFile().mkdirs();
    }
}
