package io.github.dhluong90.bpm.process.api;

import java.util.List;

/**
 * The result of a broadcast.
 *
 * @param signalId the id of the broadcast
 * @param name the signal name
 * @param waiters how many waiting subscriptions of the tenant the broadcast will wake
 * @param startedInstances the ids of the instances a signal start event started
 */
public record SignalBroadcast(String signalId, String name, int waiters, List<String> startedInstances) {

    public SignalBroadcast {
        startedInstances = startedInstances == null ? List.of() : List.copyOf(startedInstances);
    }
}
