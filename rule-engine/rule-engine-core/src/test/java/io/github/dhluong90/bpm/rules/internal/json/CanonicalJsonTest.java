package io.github.dhluong90.bpm.rules.internal.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RFC 8785 vectors: the number examples of appendix B and the sample of section 3.2.4. */
class CanonicalJsonTest {

    @ParameterizedTest
    @CsvSource({
        "0, 0",
        "-0.0, 0",
        "1e30, 1e+30",
        "4.50, 4.5",
        "2e-3, 0.002",
        "0.000001, 0.000001",
        "1e-7, 1e-7",
        "100, 100",
        "333333333.33333329, 333333333.3333333",
        "1e21, 1e+21",
        "999999999999999900000, 999999999999999900000",
        "-5e-324, -5e-324",
        "9007199254740991, 9007199254740991",
        "295147905179352830000, 295147905179352830000"
    })
    void numbers_use_the_ecmascript_form(String input, String expected) {
        assertThat(CanonicalJson.number(new BigDecimal(input))).isEqualTo(expected);
    }

    @Test
    void the_rfc_sample_object_is_sorted_and_escaped() {
        String input = "{\"numbers\": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001],"
                + " \"string\": \"\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\\"\\/\","
                + " \"literals\": [null, true, false]}";

        String canonical = CanonicalJson.write(EngineJson.JSON.readTree(input));

        assertThat(canonical).isEqualTo("{\"literals\":[null,true,false],"
                + "\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                + "\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}");
    }

    @Test
    void members_sort_by_utf16_code_units() {
        String canonical = CanonicalJson.write(EngineJson.JSON.readTree("{\"b\":1,\"a\":2,\"é\":3,\"Z\":4}"));

        assertThat(canonical).isEqualTo("{\"Z\":4,\"a\":2,\"b\":1,\"é\":3}");
    }
}
