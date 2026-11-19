package com.horus.bpm.host.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The published schemas in {@code cockpit/api/schema}, read from the repository, never a copy. */
final class JsonSchemas {

    static final Path SCHEMA_DIR = Path.of(System.getProperty("bpm.schema.dir", "../cockpit/api/schema"));

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private JsonSchemas() {
    }

    static Schema load(String fileName) {
        try {
            return REGISTRY.getSchema(Files.readString(SCHEMA_DIR.resolve(fileName)), InputFormat.JSON);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void assertValid(String fileName, String json) {
        List<Error> errors = load(fileName).validate(json, InputFormat.JSON);
        assertThat(errors).as("%s against %s", json, fileName).isEmpty();
    }
}
