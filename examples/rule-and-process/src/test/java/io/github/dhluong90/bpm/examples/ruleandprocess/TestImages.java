package io.github.dhluong90.bpm.examples.ruleandprocess;

/**
 * Container images the tests start. Each is pinned by digest in one property of the root pom.xml, which
 * Surefire and Failsafe pass to the tests; CI sets the PostgreSQL image per matrix leg. No test names an
 * image itself (PD-100: test-only images are pinned by digest).
 */
public final class TestImages {

    private TestImages() {
    }

    /** PostgreSQL, from {@code bpm.test.postgres.image}. */
    public static String postgres() {
        return required("bpm.test.postgres.image");
    }

    private static String required(String property) {
        String image = System.getProperty(property);
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(property + " is not set. Run the tests through Maven, which sets it from the"
                    + " root pom.xml, or pass -D" + property + "=<name>@sha256:<digest>.");
        }
        return image;
    }
}
