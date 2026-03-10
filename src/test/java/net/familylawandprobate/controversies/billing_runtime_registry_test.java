package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

public class billing_runtime_registry_test {

    @Test
    void registry_returns_same_engine_for_same_tenant_and_isolated_engines_across_tenants() {
        String tenantA = "tenant-a-" + UUID.randomUUID();
        String tenantB = "tenant-b-" + UUID.randomUUID();
        try {
            billing_accounting a1 = billing_runtime_registry.tenantLedger(tenantA);
            billing_accounting a2 = billing_runtime_registry.tenantLedger(tenantA);
            billing_accounting b1 = billing_runtime_registry.tenantLedger(tenantB);

            assertSame(a1, a2);
            assertNotSame(a1, b1);

            a1.recordTrustDeposit("matter-a", 10_000L, "USD", "2026-03-10T10:00:00Z", "A");
            b1.recordTrustDeposit("matter-b", 20_000L, "USD", "2026-03-10T10:00:00Z", "B");

            assertEquals(10_000L, a2.matterTrustBalance("matter-a"));
            assertEquals(0L, a2.matterTrustBalance("matter-b"));
            assertEquals(20_000L, b1.matterTrustBalance("matter-b"));
        } finally {
            billing_runtime_registry.clearTenant(tenantA);
            billing_runtime_registry.clearTenant(tenantB);
        }
    }

    @Test
    void clear_tenant_resets_that_tenant_engine_only() {
        String tenantA = "tenant-a-" + UUID.randomUUID();
        String tenantB = "tenant-b-" + UUID.randomUUID();
        try {
            billing_accounting a = billing_runtime_registry.tenantLedger(tenantA);
            billing_accounting b = billing_runtime_registry.tenantLedger(tenantB);
            a.recordTrustDeposit("matter-a", 5_000L, "USD", "2026-03-10T10:00:00Z", "A");
            b.recordTrustDeposit("matter-b", 8_000L, "USD", "2026-03-10T10:00:00Z", "B");

            billing_runtime_registry.clearTenant(tenantA);
            billing_accounting aNew = billing_runtime_registry.tenantLedger(tenantA);

            assertNotSame(a, aNew);
            assertEquals(0L, aNew.matterTrustBalance("matter-a"));
            assertEquals(8_000L, b.matterTrustBalance("matter-b"));
        } finally {
            billing_runtime_registry.clearTenant(tenantA);
            billing_runtime_registry.clearTenant(tenantB);
        }
    }

    @Test
    void tenant_ledger_requires_non_blank_tenant() {
        assertThrows(IllegalArgumentException.class, () -> billing_runtime_registry.tenantLedger(""));
        assertThrows(IllegalArgumentException.class, () -> billing_runtime_registry.tenantLedger("   "));
    }
}
