package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * RLE-100 and #450: the retention requirement follows the engine version written by the build into
 * {@code rule-engine.properties}, not the jar manifest. Each test repackages this starter's compiled classes the way a
 * host's shade step does (one flat jar, a manifest with no {@code Implementation-Version}), or lays them out as a class
 * directory, loads them in a fresh class loader and starts the retention check on {@code prod} with retention unset.
 */
class RetentionVersionPackagingTest {

    private static final String PACKAGE = DecisionLogRetention.class.getPackageName();
    private static final String PACKAGE_PATH = PACKAGE.replace('.', '/');

    @TempDir
    Path dir;

    @Test
    void RLE_100_a_1_0_engine_in_a_shaded_jar_without_a_manifest_version_refuses_start_up_on_prod() throws Exception {
        // Given Rule Engine 1.0.0 repackaged into a shaded jar whose manifest has no Implementation-Version
        Path jar = shadedJar("1.0.0");

        // When the host starts on prod with retention unset
        Throwable refusal = startOnProd(jar);

        // Then start-up is refused, naming the setting and both choices
        assertThat(refusal).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("horus.bpm.rules.decision-log.retention")
                .hasMessageContaining("keep-forever").hasMessageContaining("P12M");
    }

    @Test
    void RLE_100_a_1_0_engine_run_from_a_class_directory_refuses_start_up_on_prod() throws Exception {
        // Given Rule Engine 1.0.0 run from a class directory, so there is no jar and no manifest at all
        Path classes = classDirectory("1.0.0");

        // When the host starts on prod with retention unset, then start-up is refused the same way
        assertThat(startOnProd(classes)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("horus.bpm.rules.decision-log.retention");
    }

    @Test
    void RLE_100_a_0_3_engine_in_a_shaded_jar_still_warns_and_starts() throws Exception {
        // Given Rule Engine 0.3.0 in a shaded jar, when it starts on prod with retention unset, then it starts
        assertThat(startOnProd(shadedJar("0.3.0"))).isNull();
    }

    @Test
    void RLE_100_an_unparseable_build_version_fails_closed() throws Exception {
        // Given a build whose version cannot be read, such as an unfiltered resource from an IDE build
        assertThat(startOnProd(shadedJar("${project.version}"))).isInstanceOf(IllegalStateException.class);
        assertThat(startOnProd(shadedJar("not-a-version"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void RLE_100_the_starter_ships_its_build_version_as_a_resource_beside_the_check() {
        assertThatCode(RuleEngineModule::engineVersion).doesNotThrowAnyException();
        assertThat(RuleEngineModule.engineVersion()).isEqualTo(System.getProperty("rule-engine.version"));
    }

    /** Starts the repackaged retention check; returns what refused start-up, or null when it started. */
    private Throwable startOnProd(Path classPathEntry) throws Exception {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        try (URLClassLoader loader = new PackageFirstLoader(classPathEntry.toUri().toURL())) {
            Class<?> retention = Class.forName(PACKAGE + ".DecisionLogRetention", true, loader);
            assertThat(retention.getClassLoader()).isSameAs(loader);
            Method check = retention.getDeclaredMethod("refuseWhenRequiredAndUnset", boolean.class, Environment.class);
            check.setAccessible(true);
            try {
                check.invoke(null, true, prod);
                return null;
            } catch (InvocationTargetException e) {
                return e.getCause();
            }
        }
    }

    private Path shadedJar(String builtVersion) throws IOException {
        Path jar = dir.resolve("host-" + builtVersion.hashCode() + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (Path classFile : starterClasses()) {
                out.putNextEntry(new JarEntry(PACKAGE_PATH + "/" + classFile.getFileName()));
                Files.copy(classFile, out);
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry(PACKAGE_PATH + "/rule-engine.properties"));
            writeVersion(out, builtVersion);
            out.closeEntry();
        }
        return jar;
    }

    private Path classDirectory(String builtVersion) throws IOException {
        Path root = dir.resolve("classes-" + builtVersion.hashCode());
        Path pkg = Files.createDirectories(root.resolve(PACKAGE_PATH));
        for (Path classFile : starterClasses()) {
            Files.copy(classFile, pkg.resolve(classFile.getFileName().toString()));
        }
        try (OutputStream out = Files.newOutputStream(pkg.resolve("rule-engine.properties"))) {
            writeVersion(out, builtVersion);
        }
        return root;
    }

    private static void writeVersion(OutputStream out, String version) throws IOException {
        out.write(("engine-version=" + version + "\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private static List<Path> starterClasses() throws IOException {
        URL location = DecisionLogRetention.class.getProtectionDomain().getCodeSource().getLocation();
        Path pkg = Path.of(java.net.URI.create(location.toString())).resolve(PACKAGE_PATH);
        try (Stream<Path> files = Files.list(pkg)) {
            return files.filter(f -> f.toString().endsWith(".class")).toList();
        }
    }

    /** Loads this starter's package from the given entry only, and everything else from the test class path. */
    private static final class PackageFirstLoader extends URLClassLoader {

        PackageFirstLoader(URL entry) {
            super(new URL[] {entry}, RetentionVersionPackagingTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.startsWith(PACKAGE + ".") && name.indexOf('.', PACKAGE.length() + 1) < 0) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : findClass(name);
                }
                return super.loadClass(name, resolve);
            }
        }

        @Override
        public URL getResource(String name) {
            if (name.startsWith(PACKAGE_PATH + "/")) {
                return findResource(name);
            }
            return super.getResource(name);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            URL url = getResource(name);
            try {
                return url == null ? null : url.openStream();
            } catch (IOException e) {
                return null;
            }
        }
    }
}
