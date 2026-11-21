package io.github.dhluong90.bpm.process.api;

import java.util.List;

/**
 * Reads the incidents of an instance (#58). The tenant comes from the tenant resolver, never from a parameter. Retry
 * and resolve follow with #68. Safe for concurrent use.
 */
public interface IncidentService {

    /**
     * @param instanceId an instance of the caller's tenant
     * @return its open incidents, oldest first
     * @throws InstanceNotFoundException when the tenant has no such instance, also when another tenant has it
     * @throws PermissionDeniedException without {@code processes:read}
     */
    List<Incident> openIncidents(String instanceId);
}
