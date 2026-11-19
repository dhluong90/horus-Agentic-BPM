package io.github.dhluong90.bpm.rules.api;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Input names that look like personal data (PD-099 mitigation 1, RLE-049). An input whose rule set declares no
 * {@code mask} and whose name matches one of these gets a warning at import and at the publication request, so an
 * author who forgot {@code mask: true} on a national id finds out before the append-only decision log does.
 *
 * <p>Names are compared as words, never as regular expressions, so a configured name cannot be slow to match. Both
 * the configured name and the input name are split into words at spaces, punctuation, case changes and digits,
 * lower-cased, and have their accents removed ({@code đ} becomes {@code d}). A configured name matches when its
 * words, joined, equal some consecutive words of the input name, joined. So "national id" matches
 * {@code nationalId}, {@code national_id}, {@code NATIONAL_ID}, {@code nationalid} and
 * {@code customerNationalIdNumber}; "ngày sinh" matches {@code ngaySinh}; and "pan" never matches {@code company},
 * because a match never starts or ends inside a word.
 *
 * <p>Only names are checked, never values. Immutable and thread-safe.
 */
public final class PersonalDataNames {

    /**
     * The built-in list, in English and in Vietnamese without accents (accents are ignored when matching): national
     * id, passport, phone, email, address, date of birth and account number, and close variants.
     */
    public static final List<String> BUILT_IN = List.of(
            // National identity documents.
            "national id", "citizen id", "identity number", "identity card", "id number", "id card", "ssn",
            "social security number", "cccd", "cmnd", "can cuoc", "so dinh danh",
            // Passport.
            "passport", "ho chieu",
            // Phone.
            "phone", "telephone", "mobile number", "mobile no", "msisdn", "so dien thoai", "dien thoai", "sdt",
            // Email.
            "email", "thu dien tu",
            // Address.
            "address", "dia chi",
            // Date of birth.
            "date of birth", "birth date", "birthday", "dob", "ngay sinh",
            // Account and card numbers.
            "account number", "account no", "iban", "card number", "so tai khoan", "stk", "so the");

    /** Longest configured name accepted, in characters. */
    public static final int MAX_NAME_LENGTH = 100;

    /** Most configured names accepted in one list. */
    public static final int MAX_NAMES = 500;

    private static final PersonalDataNames BUILT_IN_NAMES = new PersonalDataNames(BUILT_IN);

    /** Joined words of each configured name to its place in {@link #names}. */
    private final Map<String, Integer> rankByKey;
    /** The names as configured, in list order, without duplicates. */
    private final List<String> names;
    private final int longestKey;

    private PersonalDataNames(Collection<String> names) {
        Objects.requireNonNull(names, "names must not be null");
        Map<String, String> keys = new LinkedHashMap<>();
        for (String name : names) {
            keys.putIfAbsent(key(name), name.strip());
        }
        if (keys.size() > MAX_NAMES) {
            throw new IllegalArgumentException("A personal-data name list holds at most " + MAX_NAMES + " names, was "
                    + keys.size());
        }
        Map<String, Integer> ranks = new LinkedHashMap<>();
        keys.keySet().forEach(key -> ranks.put(key, ranks.size()));
        this.rankByKey = Map.copyOf(ranks);
        this.names = List.copyOf(keys.values());
        this.longestKey = keys.keySet().stream().mapToInt(String::length).max().orElse(0);
    }

    /**
     * Returns the built-in list.
     *
     * @return {@link #BUILT_IN}
     */
    public static PersonalDataNames builtIn() {
        return BUILT_IN_NAMES;
    }

    /**
     * Returns a list of exactly these names; an empty list matches nothing.
     *
     * @param names the names, each 1 to {@value #MAX_NAME_LENGTH} characters with at least one letter or digit
     * @return the list
     * @throws IllegalArgumentException for a blank or too long name, or more than {@value #MAX_NAMES} names
     */
    public static PersonalDataNames of(Collection<String> names) {
        return new PersonalDataNames(names);
    }

    /**
     * Returns this list extended with more names.
     *
     * @param more the names to add
     * @return the extended list; this one when {@code more} is empty
     * @throws IllegalArgumentException as {@link #of(Collection)}
     */
    public PersonalDataNames plus(Collection<String> more) {
        Objects.requireNonNull(more, "more must not be null");
        if (more.isEmpty()) {
            return this;
        }
        List<String> all = new ArrayList<>(names);
        all.addAll(more);
        return new PersonalDataNames(all);
    }

    /**
     * Returns the names, as configured, in list order, without duplicates.
     *
     * @return the names
     */
    public List<String> names() {
        return names;
    }

    /**
     * Finds the configured name an input name matches.
     *
     * @param inputName the input name, for example {@code nationalId}
     * @return the first configured name, in list order, that matches; empty when none does
     */
    public Optional<String> match(String inputName) {
        if (inputName == null || names.isEmpty()) {
            return Optional.empty();
        }
        List<String> words = words(inputName);
        int best = Integer.MAX_VALUE;
        for (int start = 0; start < words.size(); start++) {
            StringBuilder joined = new StringBuilder();
            for (int end = start; end < words.size() && joined.length() < longestKey; end++) {
                joined.append(words.get(end));
                Integer rank = rankByKey.get(joined.toString());
                if (rank != null && rank < best) {
                    best = rank;
                }
            }
        }
        return best == Integer.MAX_VALUE ? Optional.empty() : Optional.of(names.get(best));
    }

    /** The words of a configured name, joined; refuses a name that has none. */
    private static String key(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("A personal-data name must be 1 to " + MAX_NAME_LENGTH
                    + " characters, was " + (name == null ? "null" : "'" + name + "'"));
        }
        String key = String.join("", words(name));
        if (key.isEmpty()) {
            throw new IllegalArgumentException("A personal-data name needs a letter or a digit, was '" + name + "'");
        }
        return key;
    }

    /**
     * Splits a name into lower-case words without accents: at every character that is not a letter or a digit, at a
     * change from lower to upper case, between letters and digits, and before the last capital of a run of capitals
     * followed by a lower-case letter ({@code SSNNumber} is {@code ssn}, {@code number}).
     */
    static List<String> words(String name) {
        String folded = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flush(word, words);
                continue;
            }
            if (!word.isEmpty()) {
                char previous = folded.charAt(i - 1);
                boolean lowerToUpper = Character.isLowerCase(previous) && Character.isUpperCase(c);
                boolean letterDigit = Character.isDigit(previous) != Character.isDigit(c);
                boolean acronymEnd = Character.isUpperCase(previous) && Character.isUpperCase(c)
                        && i + 1 < folded.length() && Character.isLowerCase(folded.charAt(i + 1));
                if (lowerToUpper || letterDigit || acronymEnd) {
                    flush(word, words);
                }
            }
            word.append(c);
        }
        flush(word, words);
        return words;
    }

    private static void flush(StringBuilder word, List<String> words) {
        if (!word.isEmpty()) {
            words.add(word.toString().toLowerCase(Locale.ROOT));
            word.setLength(0);
        }
    }

    @Override
    public String toString() {
        return "PersonalDataNames" + names;
    }
}
