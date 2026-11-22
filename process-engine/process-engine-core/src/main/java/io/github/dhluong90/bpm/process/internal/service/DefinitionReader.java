package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.util.List;
import java.util.function.Function;

/**
 * Reads a definition document. A plain {@code Function<byte[], Process>} is enough for a reader that has nothing to
 * say beyond the process; this one also reports what it read but ignored, so a successful import can say so (#380).
 */
public interface DefinitionReader extends Function<byte[], Process> {

    /**
     * What a read produced.
     *
     * @param process the process
     * @param warnings what was kept in the stored source but not read, such as {@code FOREIGN_EXTENSION_IGNORED}
     */
    record Result(Process process, List<DefinitionProblem> warnings) {

        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * @param source the document bytes
     * @return the process and the warnings
     */
    Result readWithWarnings(byte[] source);

    @Override
    default Process apply(byte[] source) {
        return readWithWarnings(source).process();
    }
}
