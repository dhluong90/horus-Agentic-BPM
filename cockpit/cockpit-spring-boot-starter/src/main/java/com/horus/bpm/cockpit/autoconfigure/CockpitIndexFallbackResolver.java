package com.horus.bpm.cockpit.autoconfigure;

import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * History routing: a cockpit route such as {@code rules/card-fee}, reloaded or bookmarked, gets
 * {@code index.html}, and the application shows the route. A path that names a file (a dot in its last
 * segment) or anything under {@code assets/} is never replaced, so a missing file stays a 404.
 */
final class CockpitIndexFallbackResolver extends PathResourceResolver {

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
        Resource resource = super.getResource(resourcePath, location);
        if (resource != null || !isRoute(resourcePath)) {
            return resource;
        }
        Resource index = location.createRelative("index.html");
        return index.isReadable() ? index : null;
    }

    static boolean isRoute(String resourcePath) {
        if (resourcePath.startsWith("assets/")) {
            return false;
        }
        String lastSegment = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        return !lastSegment.contains(".");
    }
}
