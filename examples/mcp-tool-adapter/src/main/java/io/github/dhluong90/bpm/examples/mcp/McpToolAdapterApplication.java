package io.github.dhluong90.bpm.examples.mcp;

import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * A host that embeds both engines and registers one {@link AgentAdapter} over an MCP server ({@code example.mcp.*}).
 * The host's own sign-in, which gives the engines their caller and tenant, is left out; see the
 * {@code rule-and-process} example for that part.
 */
@SpringBootApplication
@EnableConfigurationProperties(McpToolSettings.class)
public class McpToolAdapterApplication {

    /**
     * @param args command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(McpToolAdapterApplication.class, args);
    }

    /**
     * The adapter agent steps name as {@code horus:agent="mcp-risk"}.
     *
     * @param settings {@code example.mcp.*}
     * @return the adapter
     */
    @Bean
    AgentAdapter mcpToolAdapter(McpToolSettings settings) {
        return new McpToolAdapter(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }
}
