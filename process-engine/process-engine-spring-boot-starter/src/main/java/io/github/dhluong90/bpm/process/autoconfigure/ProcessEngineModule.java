package io.github.dhluong90.bpm.process.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The engine version shown by {@code /api/bpm/meta}, filtered into the jar by Maven. */
final class ProcessEngineModule {

    private static final String VERSION_RESOURCE = "process-engine.properties";

    private ProcessEngineModule() {
    }

    static String engineVersion() {
        try (InputStream in = ProcessEngineModule.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The Process Engine starter is missing " + VERSION_RESOURCE
                        + "; rebuild it with Maven.");
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("engine-version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
