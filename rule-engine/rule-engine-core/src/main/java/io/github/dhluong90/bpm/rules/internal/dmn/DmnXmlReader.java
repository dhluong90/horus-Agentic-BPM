package io.github.dhluong90.bpm.rules.internal.dmn;

import io.github.dhluong90.bpm.rules.internal.dmn.model.DmnModel;
import java.io.InputStream;

/**
 * Reads DMN 1.1 to 1.5 XML into the semantic model (#200). Hardened: no DTD, no external entity, size and depth
 * limits, Java and PMML functions refused by name with element id and line. Pure: no clock, no network. Oracle: every
 * TCK model reads (count), read-write-read equality, and an XML attack suite.
 */
public interface DmnXmlReader {

    /** @return the reader of this release; the stub until #200 */
    static DmnXmlReader create() {
        return source -> {
            throw new UnsupportedOperationException("DMN XML reader is not yet implemented (#200)");
        };
    }

    /**
     * @param source the XML bytes
     * @return the model, with expressions kept as text
     */
    DmnModel.Definitions read(InputStream source);
}
