package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The report of a migration dry run (#74, PRE-014): what would happen if the plan's running instances moved now. A dry
 * run only reads; it changes no instance, token, history or plan row and needs no approval. Every list is sorted, and
 * every list of instance ids holds at most {@code migration.dry-run-sample-size} ids in id order.
 *
 * @param migrationId the plan
 * @param planHash the plan's hash, which an execution is bound to
 * @param definitionKey the definition key
 * @param sourceVersion the version the instances run on
 * @param targetVersion the version they would move to
 * @param valid true when nothing blocks the plan
 * @param checkedAt when the dry run read the instances
 * @param validUntil until when an execution may rely on this dry run ({@code migration.dry-run-validity} after
 *     {@code checkedAt}, PD-114)
 * @param approval the approval an execution needs
 * @param instances counts of the source version's running instances
 * @param nodes one entry per step of the source version that holds live tokens, sorted by id
 * @param conflicts what blocks the plan, sorted by code and step id; empty when {@code valid}
 * @param movable a sample of the instances that would move
 * @param impact how many instances each kind of step instruction touches (#106)
 */
public record MigrationDryRun(String migrationId, String planHash, String definitionKey, int sourceVersion,
        int targetVersion, boolean valid, Instant checkedAt, Instant validUntil, MigrationPlan.Approval approval,
        Instances instances, List<NodeMapping> nodes, List<Conflict> conflicts, InstanceSample movable,
        Impact impact) {

    /** A step the plan maps to a renamed step of the target (#106). */
    public static final String MAPPED = "MAPPED";
    /** A step the plan cancels, continuing at another step of the target (#106). */
    public static final String CANCELLED = "CANCELLED";
    /**
     * A removed step whose remote call is in flight: the instance is left on the source version and moves on a later
     * run. It does not make the plan invalid (#106, PRE-208).
     */
    public static final String STEP_IN_FLIGHT = "STEP_IN_FLIGHT";

    /** A report with no step instructions: nothing mapped, cancelled or in flight. */
    public MigrationDryRun(String migrationId, String planHash, String definitionKey, int sourceVersion,
            int targetVersion, boolean valid, Instant checkedAt, Instant validUntil, MigrationPlan.Approval approval,
            Instances instances, List<NodeMapping> nodes, List<Conflict> conflicts, InstanceSample movable) {
        this(migrationId, planHash, definitionKey, sourceVersion, targetVersion, valid, checkedAt, validUntil,
                approval, instances, nodes, conflicts, movable, Impact.NONE);
    }

    /**
     * What the plan does to the running instances.
     *
     * @param mapped instances with a token at a mapped step; each moves with its task, claim and due time
     * @param cancelled instances with work at a cancelled step that will be cancelled and continue at the named step
     * @param inFlight instances left on the source version because a call is in flight; a later run moves them
     * @param unmapped instances with a token at a step the plan does not handle; the plan is invalid while there are any
     */
    public record Impact(long mapped, long cancelled, long inFlight, long unmapped) {
        /** No instruction touches any instance. */
        public static final Impact NONE = new Impact(0, 0, 0, 0);
    }

    /** A step whose id the target does not have. */
    public static final String STEP_NOT_IN_TARGET = "STEP_NOT_IN_TARGET";
    /** A step the target has under the same id but as another step type. */
    public static final String STEP_TYPE_CHANGED = "STEP_TYPE_CHANGED";
    /** The target version is no longer active. */
    public static final String TARGET_NOT_ACTIVE = "TARGET_NOT_ACTIVE";

    public MigrationDryRun {
        Objects.requireNonNull(migrationId, "migrationId");
        nodes = List.copyOf(nodes);
        conflicts = List.copyOf(conflicts);
    }

    /**
     * @param running running instances of the source version
     * @param movable those with no live token at a conflicting step
     * @param blocked those with at least one
     */
    public record Instances(long running, long movable, long blocked) {
    }

    /**
     * How one step of the source version maps onto the target.
     *
     * @param nodeId the BPMN id of the step
     * @param name its readable name, or null
     * @param type its step type
     * @param liveTokens live tokens waiting or active at the step
     * @param mapping {@code SAME_ID}, {@code MAPPED}, {@code CANCELLED}, {@code STEP_NOT_IN_TARGET} or
     *     {@code STEP_TYPE_CHANGED}
     * @param targetNodeId the step the tokens would move to, or null when there is none
     * @param targetType the type of that step, or null
     */
    public record NodeMapping(String nodeId, String name, String type, long liveTokens, String mapping,
            String targetNodeId, String targetType) {
    }

    /**
     * One thing that blocks the plan.
     *
     * @param code {@link #STEP_NOT_IN_TARGET}, {@link #STEP_TYPE_CHANGED} or {@link #TARGET_NOT_ACTIVE}
     * @param nodeId the step, or null for {@link #TARGET_NOT_ACTIVE}
     * @param name the step's readable name, or null
     * @param affected the running instances with a live token there
     */
    public record Conflict(String code, String nodeId, String name, InstanceSample affected) {
    }

    /**
     * Some instance ids and how many there are.
     *
     * @param count how many instances there are
     * @param instanceIds at most {@code migration.dry-run-sample-size} of them, in id order
     * @param truncated true when {@code instanceIds} does not list every instance
     */
    public record InstanceSample(long count, List<String> instanceIds, boolean truncated) {

        public InstanceSample {
            instanceIds = List.copyOf(instanceIds);
        }
    }
}
