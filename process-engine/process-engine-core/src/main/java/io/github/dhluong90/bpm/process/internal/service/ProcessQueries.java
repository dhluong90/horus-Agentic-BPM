package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionSummary;
import io.github.dhluong90.bpm.process.api.InstanceActivities;
import io.github.dhluong90.bpm.process.api.InstanceQuery;
import io.github.dhluong90.bpm.process.api.InstanceSummary;
import io.github.dhluong90.bpm.process.api.NodeCounts;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import java.util.Objects;

/**
 * The engine's {@link ProcessQueryService}: the lists from {@link ProcessServices} and the live counts per node from
 * {@link LiveNodeCountService}, and the activities of one instance from {@link InstanceActivityService}. Each delegate resolves the tenant and checks the permission itself. Thread-safe.
 */
public final class ProcessQueries implements ProcessQueryService {

    private final ProcessServices lists;
    private final LiveNodeCountService liveCounts;
    private final InstanceActivityService activities;

    /**
     * @param lists definition and instance lists
     * @param liveCounts live counts per node
     * @param activities the activities of one instance
     */
    public ProcessQueries(ProcessServices lists, LiveNodeCountService liveCounts, InstanceActivityService activities) {
        this.lists = Objects.requireNonNull(lists);
        this.liveCounts = Objects.requireNonNull(liveCounts);
        this.activities = Objects.requireNonNull(activities);
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

    @Override
    public InstanceActivities activities(String instanceId, int limit) {
        return activities.activities(instanceId, limit);
    }
}
