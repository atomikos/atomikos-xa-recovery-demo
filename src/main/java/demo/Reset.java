package demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Wipes ./data (H2 databases + Atomikos transaction logs) for a clean run. */
public final class Reset {
    public static void main(String[] args) throws IOException {
        Path dataDir = TmConfig.DATA_DIR;
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
        System.out.println("Wiped " + dataDir + " -- next Phase1Fail run starts fresh.");
    }
}
