package io.github.dhluong90.bpm.rules.api;

import java.util.List;

/**
 * Runs, stores and reads simulation reports (PD-115, RLE-199, RLE-200). The tenant comes from the host's
 * {@code TenantResolver} only. A report is written once and never changes.
 */
public interface SimulationReportService {

    /**
     * Runs a simulation over sample inputs or a replay period and stores the report bound to the version's canonical
     * hash. Needs {@code rules:simulate}. With an idempotency key a repeat returns the first report and runs nothing.
     *
     * @param ruleSet the rule set name
     * @param version the version to report on, in any status
     * @param request what to run over
     * @return the stored report, with its rows
     * @throws PermissionDeniedException without {@code rules:simulate}
     * @throws InputInvalidException for no samples, too many, or a sample with an undeclared input
     * @throws QueryInvalidException for a replay period or limit out of range
     * @throws VersionNotFoundException when the caller's tenant has no such version
     */
    SimulationReport create(String ruleSet, int version, SimulationReportRequest request);

    /**
     * Reads one report. Needs {@code rules:read}; the decision ids of replayed rows are given only to a caller with
     * {@code rules:log:read}.
     *
     * @param ruleSet the rule set name
     * @param version the version
     * @param reportId the report id
     * @return the report with its rows and its status now
     * @throws SimulationReportNotFoundException when the version has no such report in the caller's tenant
     */
    SimulationReport get(String ruleSet, int version, String reportId);

    /**
     * Lists the reports of a version, newest first, at most 50, without their rows. Needs {@code rules:read}.
     *
     * @param ruleSet the rule set name
     * @param version the version
     * @return the reports; empty when none
     * @throws VersionNotFoundException when the caller's tenant has no such version
     */
    List<SimulationReport> list(String ruleSet, int version);
}
