package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * One {@code horus:output} of a {@code callProcess} step (#92, PRE-211): the parent variable {@code name} is set from
 * the called child's variable {@code from} when the child ends. Nothing the child holds is copied unless it is named
 * here.
 *
 * @param name the parent's variable (an identifier)
 * @param from the child's variable (an identifier, or identifiers joined by dots)
 */
public record OutputMapping(String name, String from) implements HorusExtension {
}
