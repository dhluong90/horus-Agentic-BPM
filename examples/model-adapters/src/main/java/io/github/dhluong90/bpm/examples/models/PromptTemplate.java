package io.github.dhluong90.bpm.examples.models;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * A versioned prompt template from the host's classpath. {@code prompts/kyc-review@p7} is the resource
 * {@code prompts/kyc-review/p7.txt}, and its version {@code p7} is the {@code promptVersion} label of every answer it
 * produced. A template names step inputs only, so the prompt carries the step's bounded inputs and nothing else:
 * {@code {{input.<name>}}} is one input (a string as it is, any other value as JSON), {@code {{inputs}}} all of them as
 * one JSON object, {@code {{memory}}} the step's declared memory as JSON. Any other placeholder is refused when the
 * template is loaded. Immutable and thread-safe.
 */
public final class PromptTemplate {

    /** {@code <path>@<version>}. */
    static final Pattern REF = Pattern.compile("^([A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*)@([A-Za-z0-9_.-]{1,32})$");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^}]*?)\\s*}}");
    private static final Pattern INPUT = Pattern.compile("^input\\.([A-Za-z_][A-Za-z0-9_]{0,63})$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String ref;
    private final String version;
    private final String text;
    private final Set<String> inputs;

    private PromptTemplate(String ref, String version, String text, Set<String> inputs) {
        this.ref = ref;
        this.version = version;
        this.text = text;
        this.inputs = inputs;
    }

    /**
     * @param ref {@code <path>@<version>}
     * @param loader the class loader the host's resources are read with
     * @return the template
     * @throws IllegalArgumentException when the reference, the resource or a placeholder is invalid
     */
    public static PromptTemplate load(String ref, ClassLoader loader) {
        Matcher m = REF.matcher(Objects.requireNonNull(ref, "ref"));
        if (!m.matches()) {
            throw new IllegalArgumentException("A prompt reference is <path>@<version>, for example prompts/kyc-review@p7");
        }
        String resource = m.group(1) + "/" + m.group(2) + ".txt";
        String text;
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Prompt template " + ref + " not found: no resource " + resource);
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> inputs = new LinkedHashSet<>();
        Matcher p = PLACEHOLDER.matcher(text);
        while (p.find()) {
            String name = p.group(1);
            Matcher input = INPUT.matcher(name);
            if (input.matches()) {
                inputs.add(input.group(1));
            } else if (!name.equals("inputs") && !name.equals("memory")) {
                throw new IllegalArgumentException("Prompt template " + ref + " has an unknown placeholder {{" + name
                        + "}}; use {{input.<name>}}, {{inputs}} or {{memory}}");
            }
        }
        return new PromptTemplate(ref, m.group(2), text, Set.copyOf(inputs));
    }

    /** @return the reference, for example {@code prompts/kyc-review@p7} */
    public String ref() {
        return ref;
    }

    /** @return the version, for example {@code p7}: the {@code promptVersion} label */
    public String version() {
        return version;
    }

    /**
     * @param stepInputs the step's bounded inputs
     * @param memory the step's declared memory
     * @return the prompt
     * @throws IllegalArgumentException naming the first input the template uses that the step does not give
     */
    public String render(Map<String, Object> stepInputs, Map<String, Object> memory) {
        for (String name : inputs) {
            if (!stepInputs.containsKey(name)) {
                throw new IllegalArgumentException("The step gives no input " + name);
            }
        }
        Matcher p = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (p.find()) {
            String name = p.group(1);
            String value;
            if (name.equals("inputs")) {
                value = JSON.writeValueAsString(stepInputs);
            } else if (name.equals("memory")) {
                value = JSON.writeValueAsString(memory);
            } else {
                Object input = stepInputs.get(name.substring("input.".length()));
                value = input instanceof String s ? s : JSON.writeValueAsString(input);
            }
            p.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        p.appendTail(out);
        return out.toString();
    }
}
