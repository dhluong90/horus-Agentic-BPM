package io.github.dhluong90.bpm.cockpit.autoconfigure;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A minimal Spring MVC host with the cockpit starter and one API endpoint of its own. */
@SpringBootConfiguration
@EnableAutoConfiguration
class CockpitTestHost {

    @RestController
    static class HostApi {
        @GetMapping("/api/host/ping")
        String ping() {
            return "pong";
        }
    }
}
