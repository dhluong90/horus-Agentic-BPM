package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Set;

/**
 * Filters of the task list of the caller's tenant (PRE-030).
 *
 * @param assignment one or more of {@code mine} (declared to the caller or claimed by them), {@code candidate} (open
 *     tasks of the caller's roles and groups) and {@code any} (every task; needs {@code processes:task:manage});
 *     empty means {@code mine} and {@code candidate}
 * @param statuses {@code OPEN}, {@code CLAIMED} or both; empty means both
 * @param role only tasks of this role, or null; narrows {@code candidate} and {@code any}
 * @param group only tasks of this group, or null; narrows {@code candidate} and {@code any}
 * @param dueBefore only tasks due before this time, or null
 * @param slaBreached only tasks whose due time has passed on the engine clock
 * @param page which page
 */
public record TaskQuery(Set<String> assignment, Set<String> statuses, String role, String group, Instant dueBefore,
        boolean slaBreached, PageRequest page) {

    public TaskQuery {
        assignment = assignment == null ? Set.of() : Set.copyOf(assignment);
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        page = page == null ? PageRequest.first() : page;
    }

    /** @return the caller's inbox: their own and their candidate tasks, open or claimed, first page */
    public static TaskQuery inbox() {
        return new TaskQuery(Set.of(), Set.of(), null, null, null, false, PageRequest.first());
    }
}
