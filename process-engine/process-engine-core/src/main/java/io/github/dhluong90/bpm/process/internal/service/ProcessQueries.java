package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionSummary;
import io.github.dhluong90.bpm.process.api.InstanceActivities;
import io.github.dhluong90.bpm.process.api.InstanceQuery;
import io.github.dhluong90.bpm.process.api.InstanceSummary;
import io.github.dhluong90.bpm.process.api.NodeCounts;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import io.github.dhluong90.bpm.process.api.TaskQuery;
import io.github.dhluong90.bpm.process.api.TaskSummary;
import io.github.dhluong90.bpm.process.internal.store.ProcessQueryStore;
import java.util.List;
import java.util.Objects;

/**
 * The engine's {@link ProcessQueryService}: the lists from {@link ProcessServices}, the live counts per node from
 * {@link LiveNodeCountService}, the hourly rollup from {@link NodeWindowStatsService} and the activities of one
 * instance from {@link InstanceActivityService}. Each delegate resolves the tenant and checks the permission itself.
 * Thread-safe.
 */
public final class ProcessQueries implements ProcessQueryService {

    private final ProcessServices lists;
    private final LiveNodeCountService liveCounts;
    private final NodeWindowStatsService windowStats;
    private final InstanceActivityService activities;
    private final TaskQueries tasks;
    private List<PendingApprovalSource> pendingSources = List.of();

    /**
     * Without the task list; {@link #tasks} is refused.
     *
     * @param lists definition and instance lists
     * @param liveCounts live counts per node
     * @param windowStats hourly rollup per node
     * @param activities the activities of one instance
     */
    public ProcessQueries(ProcessServices lists, LiveNodeCountService liveCounts, NodeWindowStatsService windowStats,
            InstanceActivityService activities) {
        this.lists = Objects.requireNonNull(lists);
        this.liveCounts = Objects.requireNonNull(liveCounts);
        this.windowStats = Objects.requireNonNull(windowStats);
        this.activities = Objects.requireNonNull(activities);
        this.tasks = null;
    }

    /**
     * @param lists definition and instance lists
     * @param liveCounts live counts per node
     * @param windowStats hourly rollup per node
     * @param activities the activities of one instance
     * @param taskStore the read store of the task list (PRE-030)
     */
    public ProcessQueries(ProcessServices lists, LiveNodeCountService liveCounts, NodeWindowStatsService windowStats,
            InstanceActivityService activities, ProcessQueryStore taskStore) {
        this.lists = Objects.requireNonNull(lists);
        this.liveCounts = Objects.requireNonNull(liveCounts);
        this.windowStats = Objects.requireNonNull(windowStats);
        this.activities = Objects.requireNonNull(activities);
        this.tasks = lists.taskQueries(Objects.requireNonNull(taskStore));
    }

    /**
     * Adds the other kinds of proposal the pending approvals list shows beside definition versions (#473): migration
     * plans and calendar changes, when the host runs those features.
     *
     * @param sources the sources, possibly empty
     * @return this
     */
    public ProcessQueries withPendingSources(List<? extends PendingApprovalSource> sources) {
        this.pendingSources = List.copyOf(sources);
        return this;
    }

    @Override
    public Page<TaskSummary> tasks(TaskQuery query) {
        if (tasks == null) {
            return ProcessQueryService.super.tasks(query);
        }
        return tasks.tasks(query);
    }

    @Override
    public Page<DefinitionSummary> definitions(PageRequest page) {
        return lists.definitions(page);
    }

    @Override
    public Page<io.github.dhluong90.bpm.process.api.PendingApproval> pendingApprovals(PageRequest page) {
        return lists.pendingApprovals(page, pendingSources);
    }

    @Override
    public Page<InstanceSummary> instances(InstanceQuery query) {
        return lists.instances(query);
    }

    @Override
    public NodeCounts liveNodeCounts(String definitionKey, int version) {
        return liveCounts.liveNodeCounts(definitionKey, version);
    }

    @Override
    public io.github.dhluong90.bpm.process.api.NodeWindowStats nodeWindowStats(String definitionKey, int version,
            java.time.Instant from, java.time.Instant to) {
        return windowStats.nodeWindowStats(definitionKey, version, from, to);
    }

    @Override
    public InstanceActivities activities(String instanceId, int limit) {
        return activities.activities(instanceId, limit);
    }
}
