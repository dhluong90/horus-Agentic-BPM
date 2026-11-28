package io.github.dhluong90.bpm.rules.internal.dmn;

import io.github.dhluong90.bpm.rules.internal.dmn.model.DmnModel;

/**
 * Writes the model as DMN 1.5 XML with diagram information (#200). Deterministic: element order is model order,
 * attributes in a fixed order, no timestamps. Oracle: read-write-read gives an equal model.
 */
public interface DmnXmlWriter {

    /** @return the writer of this release; the stub until #200 */
    static DmnXmlWriter create() {
        return model -> {
            throw new UnsupportedOperationException("DMN XML writer is not yet implemented (#200)");
        };
    }

    /**
     * @param model the model
     * @return UTF-8 XML bytes
     */
    byte[] write(DmnModel.Definitions model);
}
