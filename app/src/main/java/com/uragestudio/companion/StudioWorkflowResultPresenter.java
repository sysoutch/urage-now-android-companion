package com.uragestudio.companion;

import android.app.Activity;
import android.os.Handler;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Projects durable workflow completion records into their owning Studio screens. */
final class StudioWorkflowResultPresenter implements AutoCloseable {
    private final WorkflowJobStore jobs;
    private final Handler main;
    private final MediaPreviewController previews;
    private final Consumer<Exception> errors;
    private final Runnable onNewResult;
    private final Supplier<DashboardApi> dashboardApi;
    private final java.util.concurrent.ExecutorService executor;
    private final Map<String, StudioWorkflowResultView> targets = new HashMap<>();
    private final Map<String, Integer> presentedJobIds = new HashMap<>();
    private final WorkflowJobStore.Observation observation;

    StudioWorkflowResultPresenter(
        Activity activity, Handler main, MediaPreviewController previews,
        Supplier<DashboardApi> dashboardApi, java.util.concurrent.ExecutorService executor,
        Consumer<Exception> errors, Runnable onNewResult
    ) {
        jobs = new WorkflowJobStore(activity);
        this.main = main;
        this.previews = previews;
        this.dashboardApi = dashboardApi;
        this.executor = executor;
        this.errors = errors;
        this.onNewResult = onNewResult;
        observation = jobs.observe(() -> main.post(this::refresh));
    }

    void bind(String workflowKind, StudioWorkflowResultView target) {
        targets.put(workflowKind, target);
        refreshKind(workflowKind, target);
    }

    private void refresh() {
        for (Map.Entry<String, StudioWorkflowResultView> target : targets.entrySet()) {
            refreshKind(target.getKey(), target.getValue());
        }
    }

    private void refreshKind(String workflowKind, StudioWorkflowResultView target) {
        WorkflowJobStore.Job completed = jobs.list().stream()
            .filter(job -> workflowKind.equals(job.kind()) && "completed".equals(job.state()) && job.result() != null)
            .findFirst().orElse(null);
        if (completed == null) return;
        MediaItem item = completed.result();
        target.showResult(item, dashboardApi, executor, main);
        if ("model3d".equals(item.kind())) {
            previews.bindModelPreview(item, target.modelPreview());
        }
        target.setClickable(true);
        target.setFocusable(true);
        target.setOnClickListener(ignored -> {
            try {
                previews.show(item);
            } catch (Exception error) {
                errors.accept(error);
            }
        });
        Integer previous = presentedJobIds.put(workflowKind, completed.id());
        if (previous != null && previous != completed.id()) onNewResult.run();
    }

    @Override public void close() {
        observation.close();
    }
}
