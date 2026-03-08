package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class texas_law_sync_test {

    @Test
    void codesSyncArgsIncludeAllowPartial() {
        Path customDataDir = Path.of("build", "tmp", "texas_law_codes").toAbsolutePath().normalize();

        String[] args = texas_law_sync.codesSyncArgs(customDataDir);

        assertEquals("--target=local", args[0]);
        assertEquals("--data-dir=" + customDataDir, args[1]);
        assertTrue(Arrays.asList(args).contains("--allow-partial"));
    }
}
