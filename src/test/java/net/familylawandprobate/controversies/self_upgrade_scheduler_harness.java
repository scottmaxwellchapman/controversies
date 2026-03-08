package net.familylawandprobate.controversies;

import java.lang.reflect.Method;

/**
 * Test-only harness that executes one self-upgrade cycle in an isolated JVM.
 */
public final class self_upgrade_scheduler_harness {

    public static void main(String[] args) throws Exception {
        self_upgrade_scheduler scheduler = self_upgrade_scheduler.defaultService();
        Method m = self_upgrade_scheduler.class.getDeclaredMethod("runUpgradeCycle", String.class);
        m.setAccessible(true);
        m.invoke(scheduler, "integration-test");
    }
}
