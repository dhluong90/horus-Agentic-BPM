package com.horus.bpm.rules.internal.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** The {@code card-fee.yaml} fixture and its RFC 8785 form, written out by hand as the test vector. */
public final class CardFee {

    /** The canonical JSON of {@code card-fee.yaml}: members sorted, comments and author order gone. */
    public static final String CANONICAL = "{\"description\":\"Monthly card fee by tier and transaction count\","
            + "\"hitPolicy\":\"first\","
            + "\"inputs\":[{\"name\":\"tier\",\"type\":\"string\"},"
            + "{\"description\":\"card transactions in the month\",\"name\":\"txCount\",\"type\":\"integer\"}],"
            + "\"outputs\":[{\"name\":\"fee\",\"type\":\"money\"}],"
            + "\"ruleSet\":\"card-fee\","
            + "\"rules\":[{\"id\":\"R1\",\"then\":{\"fee\":\"0 VND\"},\"when\":{\"tier\":\"== 'GOLD'\"}},"
            + "{\"id\":\"R2\",\"then\":{\"fee\":\"10000 VND\"},\"when\":{\"txCount\":\">= 3\"}},"
            + "{\"id\":\"R3\",\"then\":{\"fee\":\"50000 VND\"},\"when\":{}}],"
            + "\"schemaVersion\":1}";

    private CardFee() {
    }

    /**
     * Returns the fixture bytes.
     *
     * @return the YAML file as stored in Git
     */
    public static byte[] yaml() {
        return resource("/rules/card-fee.yaml");
    }

    /**
     * Returns a classpath resource.
     *
     * @param path the absolute resource path
     * @return its bytes
     */
    public static byte[] resource(String path) {
        try (InputStream in = CardFee.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
