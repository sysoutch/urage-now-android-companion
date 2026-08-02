package com.uragestudio.companion;

import android.content.Context;
import android.os.Handler;

/** Binds persisted workflow-job state to navigation badges for the Activity lifecycle. */
final class WorkflowJobRailBinder {
    private final WorkflowJobStore store;
    private final Handler main;
    private final WorkspaceRailController rail;
    private WorkflowJobStore.Observation observation;

    WorkflowJobRailBinder(Context context, Handler main, WorkspaceRailController rail) {
        store = new WorkflowJobStore(context);
        this.main = main;
        this.rail = rail;
    }

    void start() {
        if (observation != null) return;
        observation = store.observe(() -> main.post(() -> rail.updateJobCounts(store.activeCounts())));
    }

    void stop() {
        if (observation == null) return;
        observation.close();
        observation = null;
    }
}
