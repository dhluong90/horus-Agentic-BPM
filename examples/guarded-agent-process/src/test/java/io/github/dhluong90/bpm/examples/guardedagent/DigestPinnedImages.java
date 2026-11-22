package io.github.dhluong90.bpm.examples.guardedagent;

import java.util.regex.Pattern;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.ImageNameSubstitutor;

/**
 * Refuses to start any container whose image is not pinned by digest (PD-100), the Ryuk reaper included.
 * Registered as {@code image.substitutor} in src/test/resources/testcontainers.properties; Testcontainers
 * runs it after its own substitutions, so it sees the image that is actually pulled.
 */
public final class DigestPinnedImages extends ImageNameSubstitutor {

    static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

    @Override
    public DockerImageName apply(DockerImageName original) {
        if (!DIGEST.matcher(original.getVersionPart()).matches()) {
            throw new IllegalStateException("Container image " + original.asCanonicalNameString()
                    + " is not pinned by digest. Use <name>@sha256:<digest> (PD-100).");
        }
        return original;
    }

    @Override
    protected String getDescription() {
        return "refuses container images that are not pinned by digest";
    }
}
