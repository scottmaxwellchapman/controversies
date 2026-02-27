package net.familylawandprobate.controversies.plugins;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PluginManagerTest {

    static final AtomicInteger LOAD_COUNT = new AtomicInteger();
    static final AtomicInteger SHUTDOWN_COUNT = new AtomicInteger();

    public static final class TestMenuPlugin implements ControversiesPlugin {
        @Override
        public String id() {
            return "test.menu.plugin";
        }

        @Override
        public String displayName() {
            return "Test Menu Plugin";
        }

        @Override
        public int startupOrder() {
            return 7;
        }

        @Override
        public void onLoad(PluginContext context) {
            LOAD_COUNT.incrementAndGet();
        }

        @Override
        public List<MenuContribution> menuContributions() {
            return List.of(new MenuContribution("Plugins", "Test Plugin Page", "/test_plugin.jsp", 7));
        }

        @Override
        public void onShutdown() {
            SHUTDOWN_COUNT.incrementAndGet();
        }
    }

    @BeforeEach
    void resetCounters() {
        LOAD_COUNT.set(0);
        SHUTDOWN_COUNT.set(0);
    }

    @Test
    void classpathPluginLoadsAndContributesMenu() throws Exception {
        Path pluginsDir = Files.createTempDirectory("plugins-dir");
        PluginManager pm = new PluginManager(pluginsDir, getClass().getClassLoader());

        pm.start();
        try {
            List<MenuContribution> menu = pm.menuContributions();
            assertFalse(menu.isEmpty());
            assertTrue(menu.stream().anyMatch(m ->
                    "Test Plugin Page".equals(m.label()) && "/test_plugin.jsp".equals(m.href())));

            List<PluginDescriptor> descriptors = pm.descriptors();
            assertTrue(descriptors.stream().anyMatch(d ->
                    "test.menu.plugin".equals(d.id()) && d.active()));
            assertEquals(1, LOAD_COUNT.get());
        } finally {
            pm.stop();
        }

        assertEquals(1, SHUTDOWN_COUNT.get());
    }

    @Test
    void disabledPluginIsSkippedByConfig() throws Exception {
        Path pluginsDir = Files.createTempDirectory("plugins-dir-disabled");
        Files.writeString(
                pluginsDir.resolve("plugins.properties"),
                "disabled.ids=test.menu.plugin\n",
                StandardCharsets.UTF_8
        );

        PluginManager pm = new PluginManager(pluginsDir, getClass().getClassLoader());
        pm.start();
        try {
            assertTrue(pm.menuContributions().isEmpty());
            assertEquals(0, LOAD_COUNT.get());

            List<PluginDescriptor> descriptors = pm.descriptors();
            assertTrue(descriptors.stream().anyMatch(d ->
                    "test.menu.plugin".equals(d.id()) && !d.active()));
        } finally {
            pm.stop();
        }
    }
}

