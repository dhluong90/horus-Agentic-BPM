package io.github.dhluong90.bpm.process.bpmn.xml;

import io.github.dhluong90.bpm.process.bpmn.xml.codec.ActivityCodec;
import io.github.dhluong90.bpm.process.bpmn.xml.codec.ArtifactCodec;
import io.github.dhluong90.bpm.process.bpmn.xml.codec.CollaborationCodec;
import io.github.dhluong90.bpm.process.bpmn.xml.codec.DataCodec;
import io.github.dhluong90.bpm.process.bpmn.xml.codec.DiCodec;
import io.github.dhluong90.bpm.process.bpmn.xml.codec.EventCodec;
import io.github.dhluong90.bpm.process.bpmn.xml.codec.ExtensionCodec;
import io.github.dhluong90.bpm.process.bpmn.xml.codec.GatewayCodec;
import io.github.dhluong90.bpm.process.bpmn.xml.codec.ProcessCodec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.namespace.QName;

/**
 * Every element codec. Fixed by the contract: no stream edits this file. Each element name is handled by exactly
 * one codec.
 */
public final class BpmnCodecs {

    private final List<ElementCodec<?>> codecs = List.of(new ProcessCodec(), new ActivityCodec(), new EventCodec(),
            new GatewayCodec(), new DataCodec(), new ExtensionCodec(), new DiCodec(), new CollaborationCodec(),
            new ArtifactCodec());
    private final Map<QName, ElementCodec<?>> byName = new HashMap<>();

    /** Builds the dispatch table; two codecs for one element name is a programming error. */
    public BpmnCodecs() {
        for (ElementCodec<?> codec : codecs) {
            for (QName name : codec.handles()) {
                if (byName.putIfAbsent(name, codec) != null) {
                    throw new IllegalStateException("Two codecs handle " + name);
                }
            }
        }
    }

    /** @return every codec */
    public List<ElementCodec<?>> all() {
        return codecs;
    }

    /**
     * @param name an element name
     * @return its codec
     */
    public Optional<ElementCodec<?>> forName(QName name) {
        return Optional.ofNullable(byName.get(name));
    }
}
