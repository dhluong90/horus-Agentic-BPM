package io.github.dhluong90.bpm.rules.postgres;

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

    /** PgBouncer, from {@code bpm.test.pgbouncer.image}, for the tests behind a transaction-mode pooler. */
    public static String pgbouncer() {
        return required("bpm.test.pgbouncer.image");
    }

    /**
     * The files the PgBouncer image reads (the CloudNativePG image takes no environment variables): one database
     * {@code database} on the host {@code pg}, SCRAM sign-in, transaction pooling, listening on 5432. Copy them to
     * {@code /tmp/pgbouncer.ini} and {@code /tmp/userlist.txt}, and start the image with the command {@code /tmp/pgbouncer.ini} (its entrypoint is pgbouncer).
     */
    public static String pgbouncerIni(String database, String user, String password) {
        return String.join("\n",
                "[databases]",
                database + " = host=pg port=5432 dbname=" + database + " user=" + user + " password=" + password,
                "[pgbouncer]",
                "listen_addr = 0.0.0.0",
                "listen_port = 5432",
                "auth_type = scram-sha-256",
                "auth_file = /tmp/userlist.txt",
                "pool_mode = transaction",
                "ignore_startup_parameters = extra_float_digits",
                "unix_socket_dir = /tmp",
                "pidfile = /tmp/pgbouncer.pid",
                "") + "\n";
    }

    /** The sign-in list of {@link #pgbouncerIni}: one user with a plain-text password, which SCRAM accepts. */
    public static String pgbouncerUserlist(String user, String password) {
        return "\"" + user + "\" \"" + password + "\"\n";
    }

    private static String required(String property) {
        String image = System.getProperty(property);
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(property + " is not set. Run the tests through Maven, which sets it from the"
                    + " root pom.xml, or pass -D" + property + "=<name>@sha256:<digest>.");
        }
        if (!image.matches("[^@:]+(?::[0-9]+/[^@:]+)?@sha256:[0-9a-f]{64}")) {
            throw new IllegalStateException(property + "=" + image + " is not pinned by digest. Use <name>@sha256:<digest>,"
                    + " without a tag, which Testcontainers 2 refuses beside a digest (PD-100).");
        }
        return image;
    }
}
