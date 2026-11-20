package io.github.dhluong90.bpm.examples.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Host configuration of the example MCP tool adapter, under {@code example.mcp}. The server address, the credential
 * and the allow-list come from here, never from a process definition. An invalid value stops start-up naming the
 * property.
 *
 * @param name the adapter name a step names in {@code horus:agent}, for example {@code mcp-risk}
 * @param url the server's Streamable HTTP endpoint, for example {@code http://localhost:8931/mcp}
 * @param bearerToken the credential sent as {@code Authorization: Bearer}; from the environment, masked in
 *     {@link #toString()}
 * @param allowedTools the tools a step may name; any other tool is refused before a request is sent
 * @param toolInput the step input that names the tool; it is not passed on as a tool argument
 * @param tenantHeader the header that carries the instance's tenant to the server, whose own authorisation decides
 *     what that tenant may do
 * @param protocolVersion the MCP revision the adapter speaks and was tested against
 * @param requestTimeout the longest one HTTP request may take; the engine abandons the attempt at the step timeout
 */
@ConfigurationProperties("example.mcp")
public record McpToolSettings(
        @DefaultValue("mcp-risk") String name,
        URI url,
        String bearerToken,
        @DefaultValue List<String> allowedTools,
        @DefaultValue("tool") String toolInput,
        @DefaultValue("X-Tenant-Id") String tenantHeader,
        @DefaultValue(McpToolSettings.PROTOCOL_VERSION) String protocolVersion,
        @DefaultValue("PT30S") Duration requestTimeout) {

    /** The MCP revision this example targets and was tested against. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    private static final Pattern TOOL = Pattern.compile("^[A-Za-z0-9_.-]{1,128}$");
    private static final Pattern HEADER = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    public McpToolSettings {
        Objects.requireNonNull(url, "example.mcp.url is required");
        if (!"http".equals(url.getScheme()) && !"https".equals(url.getScheme())) {
            throw new IllegalArgumentException("example.mcp.url must be an http or https address");
        }
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("example.mcp.bearer-token is required; set it from the environment");
        }
        allowedTools = List.copyOf(allowedTools);
        if (allowedTools.isEmpty() || !allowedTools.stream().allMatch(t -> TOOL.matcher(t).matches())) {
            throw new IllegalArgumentException("example.mcp.allowed-tools must list at least one tool name");
        }
        if (!HEADER.matcher(tenantHeader).matches()) {
            throw new IllegalArgumentException("example.mcp.tenant-header must be a header name");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("example.mcp.request-timeout must be positive");
        }
    }

    /** Masks the credential. */
    @Override
    public String toString() {
        return "McpToolSettings[name=" + name + ", url=" + url + ", bearerToken=***, allowedTools=" + allowedTools
                + ", toolInput=" + toolInput + ", tenantHeader=" + tenantHeader + ", protocolVersion="
                + protocolVersion + ", requestTimeout=" + requestTimeout + "]";
    }
}
