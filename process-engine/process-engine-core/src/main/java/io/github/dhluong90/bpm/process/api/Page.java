package io.github.dhluong90.bpm.process.api;

import java.util.List;
import java.util.Objects;

/**
 * One page of a keyset-paged list.
 *
 * @param items the items of this page, in the list's order
 * @param nextCursor pass it back unchanged to read the next page; null on the last page
 * @param <T> the item type
 */
public record Page<T>(List<T> items, String nextCursor) {

    public Page {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }

    /** @return whether a next page exists */
    public boolean hasNext() {
        return nextCursor != null;
    }
}
