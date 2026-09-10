package demo;

import java.nio.file.Path;

/**
 * Atomikos configuration shared by both phases. The transaction manager's
 * unique name and log directory MUST be identical across the "crash" (Phase
 * 1) and "recover" (Phase 2) JVMs -- that's how Atomikos recognizes its own
 * leftover log on the next startup and resumes it.
 */
final class TmConfig {
    private TmConfig() {}

    static final Path DATA_DIR = Path.of("data").toAbsolutePath();
    static final Path LOG_DIR = DATA_DIR.resolve("tmlogs");

    static void apply() {
        System.setProperty("com.atomikos.icatch.log_base_dir", LOG_DIR.toString());
        System.setProperty("com.atomikos.icatch.output_dir", LOG_DIR.toString());
        System.setProperty("com.atomikos.icatch.log_base_name", "tmlog");
        System.setProperty("com.atomikos.icatch.tm_unique_name", "j-xa-tester-atomikos-demo");
        System.setProperty("com.atomikos.icatch.enable_logging", "true");
        System.setProperty("com.atomikos.icatch.console_log_level", "INFO");
        // Deterministic, sequential 2PC so the commit order in the printed
        // timeline matches the order resources were enlisted in.
        System.setProperty("com.atomikos.icatch.threaded_2pc", "false");
        System.setProperty("com.atomikos.icatch.default_jta_timeout", "300000");
        System.setProperty("com.atomikos.icatch.max_timeout", "300000");
        // How often Atomikos' background thread rescans the log for
        // in-doubt transactions and retries them. Default is 5 minutes;
        // shortened here so Phase2Recover's recovery is prompt to watch.
        System.setProperty("com.atomikos.icatch.recovery_delay", "2000");
        LOG_DIR.toFile().mkdirs();
    }
}
