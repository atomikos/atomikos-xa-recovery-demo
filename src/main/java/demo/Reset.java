package demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Wipes both scenarios' data directories (H2 databases + Atomikos
 * transaction logs) for a clean run.
 */
public final class Reset {
    public static void main(String[] args) throws IOException {
        wipe(TmConfig.DATA_DIR, "Phase1Fail");
        wipe(RollbackTmConfig.DATA_DIR, "Phase1RollbackFail");
    }

    private static void wipe(Path dataDir, String nextRun) throws IOException {
        if (Files.exists(dataDir)) {
            try (var walk = Files.walk(dataDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.println("Wiped " + dataDir + " -- next " + nextRun + " run starts fresh.");
    }
}
