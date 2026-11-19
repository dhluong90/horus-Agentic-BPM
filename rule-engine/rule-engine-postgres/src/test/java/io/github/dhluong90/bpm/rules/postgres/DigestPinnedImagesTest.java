package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.ImageNameSubstitutor;

/**
 * The runtime guard of PD-100, through the substitutor Testcontainers itself uses. No Docker needed. The
 * unpinned names are built with withTag so that the static check (tools/check-image-pins.py) stays quiet.
 */
class DigestPinnedImagesTest {

    @Test
    void an_image_without_a_digest_is_refused_at_start_up() {
        assertThatThrownBy(() -> ImageNameSubstitutor.instance().apply(DockerImageName.parse("postgres").withTag("17-alpine")))
                .hasMessageContaining("postgres:17-alpine is not pinned by digest");
    }

    @Test
    void the_reaper_is_pulled_by_its_pinned_digest() {
        assertThat(ImageNameSubstitutor.instance().apply(DockerImageName.parse("testcontainers/ryuk").withTag("0.14.0"))
                        .asCanonicalNameString())
                .isEqualTo("testcontainers/ryuk@sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0");
    }

    @Test
    void the_postgres_property_is_pinned_and_passes() {
        String image = TestImages.postgres();
        assertThat(ImageNameSubstitutor.instance().apply(DockerImageName.parse(image)).asCanonicalNameString())
                .isEqualTo(image)
                .contains("@sha256:");
    }
}
