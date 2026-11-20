package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.postgres.ProcessEngineSchemaMigrator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * At start-up: applies the {@code pre_*} migrations, then imports the configured definitions for each start-up
 * tenant through the normal import path. A document whose hash equals the latest version creates no new version,
 * so restarts are idempotent.
 */
public final class ProcessEngineInitializer implements SmartInitializingSingleton {

    private static final Log LOG = LogFactory.getLog(ProcessEngineInitializer.class);

    private final ProcessEngineSchemaMigrator migrator;
    private final ProcessServices services;
    private final ProcessEngineProperties properties;
    private final ResourceLoader resources;

    ProcessEngineInitializer(ProcessEngineSchemaMigrator migrator, ProcessServices services,
            ProcessEngineProperties properties, ResourceLoader resources) {
        this.migrator = migrator;
        this.services = services;
        this.properties = properties;
        this.resources = resources;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (properties.migrateOnStart()) {
            int applied = migrator.migrate();
            LOG.info("Process Engine schema at version " + migrator.currentVersion() + " (" + applied
                    + " migration(s) applied)");
        }
        // The list cursor key, in its own short transaction outside any request (pre_engine_key, V3).
        services.loadCursorKey();
        for (String tenant : properties.definitions().startupTenants()) {
            for (String location : properties.definitions().locations()) {
                DefinitionVersion version = services.importAtStartup(tenant,
                        new ImportRequest(SourceFormat.BPMN_XML, read(location), null));
                LOG.info("Process definition " + version.key() + " v" + version.version() + " " + version.status()
                        + " for tenant " + tenant + " from " + location + " (hash " + version.definitionHash() + ")");
            }
        }
    }

    private byte[] read(String location) {
        Resource resource = resources.getResource(location);
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read process definition " + location
                    + " (horus.bpm.processes.definitions.locations)", e);
        }
    }
}
