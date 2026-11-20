package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionSummary;
import io.github.dhluong90.bpm.process.api.InstanceQuery;
import io.github.dhluong90.bpm.process.api.InstanceSummary;
import io.github.dhluong90.bpm.process.api.NodeCounts;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import io.github.dhluong90.bpm.process.api.TaskQuery;
import io.github.dhluong90.bpm.process.api.TaskSummary;
import io.github.dhluong90.bpm.process.internal.store.ProcessQueryStore;
import java.util.Objects;

/**
 * The engine's {@link ProcessQueryService}: the lists from {@link ProcessServices} and the live counts per node from
 * {@link LiveNodeCountService}. Each delegate resolves the tenant and checks the permission itself. Thread-safe.
 */
public final class ProcessQueries implements ProcessQueryService {

    private final ProcessServices lists;
    private final LiveNodeCountService liveCounts;
    private final TaskQueries tasks;

    /**
     * Without the task list; {@link #tasks} is refused.
     *
     * @param lists definition and instance lists
     * @param liveCounts live counts per node
     */
    public ProcessQueries(ProcessServices lists, LiveNodeCountService liveCounts) {
        this.lists = Objects.requireNonNull(lists);
        this.liveCounts = Objects.requireNonNull(liveCounts);
        this.tasks = null;
    }

    /**
     * @param lists definition and instance lists
     * @param liveCounts live counts per node
     * @param taskStore the read store of the task list (PRE-030)
     */
    public ProcessQueries(ProcessServices lists, LiveNodeCountService liveCounts, ProcessQueryStore taskStore) {
        this.lists = Objects.requireNonNull(lists);
        this.liveCounts = Objects.requireNonNull(liveCounts);
        this.tasks = lists.taskQueries(Objects.requireNonNull(taskStore));
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
    public Page<InstanceSummary> instances(InstanceQuery query) {
        return lists.instances(query);
    }

    @Override
    public NodeCounts liveNodeCounts(String definitionKey, int version) {
        return liveCounts.liveNodeCounts(definitionKey, version);
    }
}
