package io.github.dhluong90.bpm.examples.ruleandprocess;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The host has no page of its own at {@code /}: after sign-in the browser lands on the cockpit instead of a 404. */
@Controller
class RootRedirectController {

    private final String cockpit;

    RootRedirectController(@Value("${horus.bpm.cockpit.path:/bpm/cockpit}") String cockpitPath) {
        this.cockpit = "redirect:" + cockpitPath + "/";
    }

    @GetMapping("/")
    String root() {
        return cockpit;
    }
}
