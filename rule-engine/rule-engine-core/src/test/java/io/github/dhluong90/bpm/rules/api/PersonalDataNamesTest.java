package io.github.dhluong90.bpm.rules.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.testing.property.Gen;
import io.github.dhluong90.bpm.testing.property.Property;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Matching input names against personal-data names (RLE-049, PD-099 mitigation 1): words, not substrings and not
 * regular expressions.
 */
class PersonalDataNamesTest {

    final PersonalDataNames builtIn = PersonalDataNames.builtIn();

    @Test
    void RLE_049_built_in_names_match_the_usual_spellings_of_an_input_name() {
        assertThat(builtIn.match("nationalId")).contains("national id");
        assertThat(builtIn.match("national_id")).contains("national id");
        assertThat(builtIn.match("NATIONAL_ID")).contains("national id");
        assertThat(builtIn.match("nationalid")).contains("national id");
        assertThat(builtIn.match("customerNationalIdNumber")).contains("national id");
        assertThat(builtIn.match("passportNo")).contains("passport");
        assertThat(builtIn.match("mobilePhone")).contains("phone");
        assertThat(builtIn.match("e_mail")).contains("email");
        assertThat(builtIn.match("customerEmail")).contains("email");
        assertThat(builtIn.match("billingAddress")).contains("address");
        assertThat(builtIn.match("DOB")).contains("dob");
        assertThat(builtIn.match("dateOfBirth")).contains("date of birth");
        assertThat(builtIn.match("SSNNumber")).contains("ssn");
        assertThat(builtIn.match("accountNumber")).contains("account number");
        assertThat(builtIn.match("ibanCode")).contains("iban");
    }

    @Test
    void RLE_049_built_in_names_cover_vietnamese_identifiers_without_accents() {
        assertThat(builtIn.match("soCccd")).contains("cccd");
        assertThat(builtIn.match("so_cmnd")).contains("cmnd");
        assertThat(builtIn.match("soDienThoai")).contains("so dien thoai");
        assertThat(builtIn.match("sodienthoai")).contains("so dien thoai");
        assertThat(builtIn.match("sdtKhachHang")).contains("sdt");
        assertThat(builtIn.match("diaChiThuongTru")).contains("dia chi");
        assertThat(builtIn.match("ngaySinh")).contains("ngay sinh");
        assertThat(builtIn.match("soTaiKhoan")).contains("so tai khoan");
        assertThat(builtIn.match("soHoChieu")).contains("ho chieu");
    }

    @Test
    void RLE_049_a_name_never_matches_inside_a_word_or_an_unrelated_input() {
        assertThat(builtIn.match("company")).isEmpty();         // holds "pan" and no word of the list
        assertThat(builtIn.match("adobeTier")).isEmpty();        // "dob" only inside a word
        assertThat(builtIn.match("paidNumber")).isEmpty();       // "id number" only across a word boundary
        assertThat(builtIn.match("amount")).isEmpty();
        assertThat(builtIn.match("tier")).isEmpty();
        assertThat(builtIn.match("segment")).isEmpty();
        assertThat(builtIn.match("productCode")).isEmpty();
        assertThat(builtIn.match(null)).isEmpty();
    }

    @Test
    void RLE_049_configured_names_ignore_accents_case_and_punctuation() {
        // Given a bank's own list, written as people write it
        PersonalDataNames bank = PersonalDataNames.of(List.of("Ngày sinh", "Mã số thuế", "tax-ID"));

        // Then identifiers in any style match it
        assertThat(bank.match("ngaySinh")).contains("Ngày sinh");
        assertThat(bank.match("ma_so_thue")).contains("Mã số thuế");
        assertThat(bank.match("customerTaxId")).contains("tax-ID");
        assertThat(bank.match("nationalId")).isEmpty();
        assertThat(bank.names()).containsExactly("Ngày sinh", "Mã số thuế", "tax-ID");
    }

    @Test
    void RLE_049_an_extended_list_keeps_the_first_match_in_list_order_and_an_empty_list_matches_nothing() {
        // Given the built-in list extended with "home address"
        PersonalDataNames extended = builtIn.plus(List.of("home address", "email"));

        // Then the built-in "email" wins over its duplicate, and "address" (earlier) wins over "home address"
        assertThat(extended.names()).hasSize(PersonalDataNames.BUILT_IN.size() + 1);
        assertThat(extended.match("homeAddress")).contains("address");
        assertThat(extended.match("emailAddress")).contains("email");
        assertThat(PersonalDataNames.of(List.of()).match("nationalId")).isEmpty();
        assertThat(builtIn.plus(List.of())).isSameAs(builtIn);
    }

    @Test
    void RLE_049_a_blank_or_too_long_name_is_refused_with_the_value() {
        assertThatThrownBy(() -> PersonalDataNames.of(List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 to 100 characters");
        assertThatThrownBy(() -> PersonalDataNames.of(List.of("--")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'--'");
        assertThatThrownBy(() -> PersonalDataNames.of(List.of("x".repeat(101))))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < PersonalDataNames.MAX_NAMES + 1; i++) {
            tooMany.add("name" + (char) ('a' + i % 26) + (char) ('a' + i / 26));
        }
        assertThatThrownBy(() -> PersonalDataNames.of(tooMany)).hasMessageContaining("at most 500");
    }

    /**
     * Every built-in entry, written in camelCase, snake_case or upper case with a word before and after, or joined
     * into one lower-case word on its own.
     */
    @TestFactory
    Stream<DynamicTest> RLE_049_any_built_in_name_in_any_identifier_style_matches() {
        Gen<String> entries = Gen.of(PersonalDataNames.BUILT_IN.toArray(String[]::new));
        Gen<String> styles = Gen.of("camel", "snake", "upper", "joined");
        Gen<String> affixes = Gen.of("", "customer", "kh", "primary", "old");
        return Property.tries(500).forAll(Gen.from(random -> List.of(entries.next(random), styles.next(random),
                affixes.next(random), affixes.next(random))), sample -> {
                    boolean joined = sample.get(1).equals("joined");
                    List<String> words = new ArrayList<>();
                    if (!joined && !sample.get(2).isEmpty()) {
                        words.add(sample.get(2));
                    }
                    words.addAll(List.of(sample.get(0).split(" ")));
                    if (!joined && !sample.get(3).isEmpty()) {
                        words.add("value" + sample.get(3));
                    }
                    String identifier = identifier(words, sample.get(1));

                    // The entry, or an earlier entry of the list that the identifier also holds, is found
                    assertThat(PersonalDataNames.builtIn().match(identifier)).as(identifier).isPresent();
                    assertThat(PersonalDataNames.of(List.of(sample.get(0))).match(identifier)).as(identifier)
                            .contains(sample.get(0));
                });
    }

    private static String identifier(List<String> words, String style) {
        return switch (style) {
            case "snake" -> String.join("_", words);
            case "upper" -> String.join("_", words).toUpperCase(Locale.ROOT);
            case "joined" -> String.join("", words);
            default -> {
                StringBuilder camel = new StringBuilder(words.getFirst());
                for (String word : words.subList(1, words.size())) {
                    camel.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                }
                yield camel.toString();
            }
        };
    }
}
