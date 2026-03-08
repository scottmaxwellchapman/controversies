package net.familylawandprobate.controversies;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Daily external data backup scheduler for tenants using external storage backends.
 */
public final class external_storage_data_sync_scheduler {

    private static final Logger LOG = Logger.getLogger(external_storage_data_sync_scheduler.class.getName());
    private static final long INITIAL_DELAY_MS = 60_000L;
    private static final long TICK_INTERVAL_MS = 60L * 60L * 1000L;

    private static final class Holder {
        private static final external_storage_data_sync_scheduler INSTANCE = new external_storage_data_sync_scheduler();
    }

    public static external_storage_data_sync_scheduler defaultService() {
        return Holder.INSTANCE;
    }

    private final Timer timer = new Timer("external-storage-data-sync-timer", true);
    private final AtomicBoolean started = new AtomicBoolean(false);

    private external_storage_data_sync_scheduler() {
    }

    public void startIfNeeded() {
        if (!started.compareAndSet(false, true)) return;
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runTick();
            }
        }, INITIAL_DELAY_MS, TICK_INTERVAL_MS);
        LOG.info("External storage data sync scheduler started.");
    }

    private void runTick() {
        try {
            List<external_storage_data_sync.BackupResult> results = external_storage_data_sync.defaultService().syncAllTenantsIfDue();
            for (int i = 0; i < (results == null ? 0 : results.size()); i++) {
                external_storage_data_sync.BackupResult r = results.get(i);
                if (r == null || r.skipped) continue;
                if (r.ok) {
                    LOG.info("External storage backup completed for source=" + r.sourceId + " snapshot=" + r.snapshotId + ".");
                } else {
                    LOG.warning("External storage backup failed for source=" + r.sourceId + ": " + r.message);
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "External storage data sync scheduler tick failed: " + ex.getMessage(), ex);
        }
    }
}
