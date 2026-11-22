package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.GraphDiff;
import io.github.dhluong90.bpm.process.api.GraphDiffPage;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.internal.json.Values;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Pages and hashes the complete change set of two versions (PRE-199, #426). Pure functions of a complete
 * {@link GraphDiff}, so a page, its cursor and the change-set hash are recomputed on every call and nothing is stored.
 *
 * <p>One SHA-256 runs over a header (both versions and their definition hashes) and then every change in sequence
 * order. The hash at the end is the change-set hash; the hash after the first {@code n} changes is the digest the cursor
 * of the page that starts at {@code n} carries, so a cursor can only be had by reading the changes before it.
 */
public final class GraphDiffPaging {

    /** Page size when the caller names none. */
    public static final int DEFAULT_LIMIT = 200;
    /** Largest page. */
    public static final int MAX_LIMIT = 500;

    private GraphDiffPaging() {
    }

    /** One change in sequence order, as its canonical line. */
    private record Item(String line) {
    }

    /**
     * @param all the complete change set
     * @return the hash of the whole change set, 64 lower-case hexadecimal characters
     */
    public static String changeSetHash(GraphDiff all) {
        return HexFormat.of().formatHex(run(all, items(all).size(), -1).digest.digest());
    }

    /**
     * @param all the complete change set
     * @return the number of changes in it
     */
    public static int total(GraphDiff all) {
        return all.nodesAdded().size() + all.nodesRemoved().size() + all.nodesChanged().size()
                + all.edgesAdded().size() + all.edgesRemoved().size();
    }

    /**
     * Reads one page.
     *
     * @param all the complete change set
     * @param limit changes per page, 1 to {@link #MAX_LIMIT}; null for {@link #DEFAULT_LIMIT}
     * @param cursor the {@code nextCursor} of the previous page; null for the first
     * @return the page
     * @throws QueryInvalidException when the limit is out of range or the cursor is not one this change set issued
     */
    public static GraphDiffPage page(GraphDiff all, Integer limit, String cursor) {
        Objects.requireNonNull(all, "all");
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new QueryInvalidException("limit must be between 1 and " + MAX_LIMIT);
        }
        List<Item> items = items(all);
        int total = items.size();
        int offset = 0;
        String expectedPrefix = null;
        if (cursor != null) {
            String[] parts = decode(cursor);
            try {
                offset = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                throw invalidCursor();
            }
            expectedPrefix = parts[1];
            if (offset < 1 || offset >= total) {
                throw invalidCursor();
            }
        }
        int end = Math.min(total, offset + size);
        Run run = run(all, end, offset);
        if (cursor != null && !run.atMark.equals(expectedPrefix)) {
            throw invalidCursor();
        }
        boolean last = end >= total;
        String nextCursor = last ? null : encode(end, run.atEnd);
        String setHash = last ? HexFormat.of().formatHex(finish(run)) : null;
        return new GraphDiffPage(slice(all, offset, end), total, offset, nextCursor, setHash);
    }

    private static byte[] finish(Run run) {
        return run.digest.digest();
    }

    /** The digest after the first {@code end} changes, with the digest after the first {@code mark} noted. */
    private record Run(MessageDigest digest, String atMark, String atEnd) {
    }

    private static Run run(GraphDiff all, int end, int mark) {
        List<Item> items = items(all);
        MessageDigest digest = sha256();
        digest.update(header(all).getBytes(StandardCharsets.UTF_8));
        String atMark = mark == 0 ? hex(digest) : null;
        for (int i = 0; i < items.size() && i < Math.max(end, 0); i++) {
            if (i == mark) {
                atMark = hex(digest);
            }
            digest.update((items.get(i).line() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        String atEnd = hex(digest);
        // The digest holds the first end changes: the whole set when end is the total, otherwise only the cursor
        // digests are used.
        return new Run(digest, atMark, atEnd);
    }

    private static String header(GraphDiff all) {
        return "change-set.v1\n" + all.definitionKey() + "\n" + all.base().version() + ":" + all.base().definitionHash()
                + "\n" + all.target().version() + ":" + all.target().definitionHash() + "\n";
    }

    private static List<Item> items(GraphDiff all) {
        List<Item> out = new ArrayList<>();
        for (GraphDiff.Node n : all.nodesAdded()) {
            out.add(new Item("A|" + join(n.id(), n.name(), n.type())));
        }
        for (GraphDiff.Node n : all.nodesRemoved()) {
            out.add(new Item("R|" + join(n.id(), n.name(), n.type())));
        }
        for (GraphDiff.NodeChange c : all.nodesChanged()) {
            out.add(new Item("C|" + join(c.id(), c.name(), c.type(), c.path(), Values.toJson(c.from()).toString(),
                    Values.toJson(c.to()).toString())));
        }
        for (GraphDiff.Edge e : all.edgesAdded()) {
            out.add(new Item("EA|" + join(e.id(), e.from(), e.to(), e.kind())));
        }
        for (GraphDiff.Edge e : all.edgesRemoved()) {
            out.add(new Item("ER|" + join(e.id(), e.from(), e.to(), e.kind())));
        }
        return out;
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            out.append(part == null ? "-1:" : part.length() + ":" + part).append('\u001f');
        }
        return out.toString();
    }

    /** The changes at sequence positions {@code [from, to)}, split back into their five lists. */
    private static GraphDiff slice(GraphDiff all, int from, int to) {
        int a = all.nodesAdded().size();
        int r = a + all.nodesRemoved().size();
        int c = r + all.nodesChanged().size();
        int ea = c + all.edgesAdded().size();
        return new GraphDiff(all.definitionKey(), all.base(), all.target(), window(all.nodesAdded(), 0, from, to),
                window(all.nodesRemoved(), a, from, to), window(all.nodesChanged(), r, from, to),
                window(all.edgesAdded(), c, from, to), window(all.edgesRemoved(), ea, from, to), false);
    }

    private static <T> List<T> window(List<T> list, int start, int from, int to) {
        int lo = Math.max(from - start, 0);
        int hi = Math.min(to - start, list.size());
        return lo >= hi ? List.of() : list.subList(lo, hi);
    }

    private static String encode(int offset, String prefixDigest) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((offset + ":" + prefixDigest).getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decode(String cursor) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = text.split(":", -1);
            if (parts.length != 2 || parts[1].length() != 64) {
                throw invalidCursor();
            }
            return parts;
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
    }

    private static QueryInvalidException invalidCursor() {
        return new QueryInvalidException("cursor is not one issued for this change set; read the first page again");
    }

    private static String hex(MessageDigest digest) {
        try {
            return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("SHA-256 digests are cloneable on every Java platform", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
