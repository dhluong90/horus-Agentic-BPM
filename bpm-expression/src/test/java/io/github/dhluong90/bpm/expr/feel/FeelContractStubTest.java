package io.github.dhluong90.bpm.expr.feel;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-0028: every stub fails by name and issue; the registry is empty until #199. */
class FeelContractStubTest {

    @Test
    void parser_stub_names_its_issue() {
        var e = assertThrows(FeelNotImplementedException.class,
                () -> FeelParser.create().parseExpression("1", FeelNameScope.empty()));
        assertTrue(e.getMessage().contains("#197"));
    }

    @Test
    void printer_stub_names_its_issue() {
        var e = assertThrows(FeelNotImplementedException.class,
                () -> FeelPrinter.create().print(new FeelNode.NullLiteral(0)));
        assertTrue(e.getMessage().contains("#197"));
    }

    @Test
    void engine_stub_names_its_issue() {
        var e = assertThrows(FeelNotImplementedException.class,
                () -> FeelEngine.create().compile(new FeelNode.NullLiteral(0), Map.of(), FeelBuiltins.create()));
        assertTrue(e.getMessage().contains("#198"));
    }

    @Test
    void builtin_registry_is_empty_until_199() {
        assertTrue(FeelBuiltins.create().find("string length").isEmpty());
    }
}
