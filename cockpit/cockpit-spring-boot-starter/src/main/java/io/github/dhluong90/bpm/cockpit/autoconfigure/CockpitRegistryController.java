package io.github.dhluong90.bpm.cockpit.autoconfigure;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the generated one-host registry next to the cockpit's {@code index.html}. Created by
 * {@link CockpitAutoConfiguration} only; the package is not meant to be component-scanned.
 */
@Controller
@ResponseBody
final class CockpitRegistryController {

    private final CockpitHostRegistry registry;

    CockpitRegistryController(CockpitHostRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("${horus.bpm.cockpit.path:/bpm/cockpit}/" + CockpitHostRegistry.FILE_NAME)
    ResponseEntity<byte[]> registry() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(registry.json());
    }
}
