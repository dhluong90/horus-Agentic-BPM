package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.PendingApproval;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The pending approvals list (#31, RLE-190): the versions of the caller's tenant that wait for a second person. The
 * tenant comes from the {@code TenantResolver} inside the service; nothing in a request names one.
 */
@RestController
@RequestMapping(RestSupport.BASE + "/approvals")
public class ApprovalController {

    private final VersionService versions;
    private final CallerResolver callers;

    /**
     * Creates the controller.
     *
     * @param versions the version service
     * @param callers resolves the caller and its permissions
     */
    public ApprovalController(VersionService versions, CallerResolver callers) {
        this.versions = Objects.requireNonNull(versions, "versions must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
    }

    /** {@code GET /approvals}: pending versions, oldest request first, keyset-paged ({@code rules:read}). */
    @GetMapping
    public ResponseEntity<byte[]> list(
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        RestSupport.require(callers, "rules:read");
        Page<PendingApproval> page = versions.pendingApprovals(new PageRequest(RestSupport.limit(limit), cursor));
        return RestSupport.json(HttpStatus.OK, RestSupport.page(page, ApprovalController::item));
    }

    private static ObjectNode item(PendingApproval a) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("ruleSet", a.ruleSet());
        node.put("version", a.version());
        node.put("canonicalHash", a.canonicalHash());
        node.put("lockVersion", a.lockVersion());
        node.put("requestedBy", a.requestedBy());
        node.put("requestedAt", a.requestedAt().toString());
        // #442: the requester as the screen shows them; the id when the host knows no name
        node.put("requestedByName", a.requestedByName());
        // #470: the date the requester asked for, so the approver sees it before approving; null means at approval
        if (a.effectiveFrom() == null) {
            node.putNull("effectiveFrom");
        } else {
            node.put("effectiveFrom", a.effectiveFrom().toString());
        }
        ArrayNode actions = node.putArray("allowedActions");
        a.allowedActions().forEach(actions::add);
        ObjectNode denied = node.putObject("deniedActions");
        a.deniedActions().forEach(denied::put);
        return node;
    }
}
