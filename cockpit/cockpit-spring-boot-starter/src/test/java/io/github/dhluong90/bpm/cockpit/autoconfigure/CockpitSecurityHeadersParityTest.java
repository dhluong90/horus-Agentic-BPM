package io.github.dhluong90.bpm.cockpit.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The starter sends CockpitSecurityHeaders; the front-end tests run the bundle under
 * cockpit/web/security-headers.ts. Both must be the same policy, or a green Playwright run proves nothing.
 */
class CockpitSecurityHeadersParityTest {

    private static final Path TYPESCRIPT = Path.of("..", "web", "security-headers.ts");

    @Test
    void TR_SEC_09_starter_and_front_end_test_server_send_the_same_content_security_policy() throws Exception {
        String source = Files.readString(TYPESCRIPT);
        Matcher array = Pattern.compile("CONTENT_SECURITY_POLICY = \\[(.*?)\\]\\.join\\('; '\\)", Pattern.DOTALL).matcher(source);
        assertThat(array.find()).as("CONTENT_SECURITY_POLICY array in %s", TYPESCRIPT).isTrue();
        List<String> directives = new ArrayList<>();
        Matcher quoted = Pattern.compile("\"([^\"]+)\"").matcher(array.group(1));
        while (quoted.find()) {
            directives.add(quoted.group(1));
        }
        assertThat(String.join("; ", directives)).isEqualTo(CockpitSecurityHeaders.CONTENT_SECURITY_POLICY);
    }

    @Test
    void TR_SEC_09_starter_and_front_end_test_server_send_the_same_other_headers() throws Exception {
        String source = Files.readString(TYPESCRIPT);
        Matcher map = Pattern.compile("SECURITY_HEADERS: Record<string, string> = \\{(.*?)\\};", Pattern.DOTALL).matcher(source);
        assertThat(map.find()).isTrue();
        Map<String, String> typescript = new LinkedHashMap<>();
        Matcher entry = Pattern.compile("'([A-Za-z-]+)': ('([^']*)'|CONTENT_SECURITY_POLICY)").matcher(map.group(1));
        while (entry.find()) {
            typescript.put(entry.group(1),
                    entry.group(3) != null ? entry.group(3) : CockpitSecurityHeaders.CONTENT_SECURITY_POLICY);
        }
        assertThat(typescript).containsExactlyInAnyOrderEntriesOf(CockpitSecurityHeaders.HEADERS);
    }
}
