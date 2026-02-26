package net.familylawandprobate.controversies;

import net.familylawandprobate.controversies.integrations.clio.ClioClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class clio_client_test {

    @Test
    void constructor_rejects_insecure_scheme() {
        assertThrows(IllegalArgumentException.class, () -> new ClioClient("http://app.clio.com"));
    }

    @Test
    void constructor_rejects_localhost_hosts() {
        assertThrows(IllegalArgumentException.class, () -> new ClioClient("https://localhost"));
        assertThrows(IllegalArgumentException.class, () -> new ClioClient("https://127.0.0.1"));
    }

    @Test
    void constructor_accepts_https_remote_host() {
        assertDoesNotThrow(() -> new ClioClient("https://app.clio.com/"));
    }
}
