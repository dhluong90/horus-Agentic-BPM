package io.github.dhluong90.bpm.process.internal.service;

/**
 * The 64-bucket duration histogram of the hourly node statistics (DESIGN section 7, PE-F-49). Bucket {@code k} covers
 * {@code [10 ms * 2^(k/2), 10 ms * 2^((k+1)/2))}; a duration under 10 ms is in bucket 0 and one beyond the last
 * boundary (about 29 days) in bucket 63. Boundaries grow by the square root of 2, so a percentile read at the
 * geometric middle of its bucket is within 19 percent of the true value. Counts only add, so slices and hours merge
 * by summing.
 */
public final class DurationHistogram {

    /** Number of buckets of the stored array. */
    public static final int BUCKETS = 64;

    private static final double FIRST_MS = 10.0;
    private static final double LN2 = StrictMath.log(2.0);

    private DurationHistogram() {
    }

    /**
     * @param durationMs the duration in milliseconds, negative counts as 0
     * @return the bucket index, 0 to 63
     */
    public static int bucket(long durationMs) {
        if (durationMs < FIRST_MS) {
            return 0;
        }
        int k = (int) Math.floor(2.0 * StrictMath.log(durationMs / FIRST_MS) / LN2 + 1e-9);
        return Math.min(Math.max(k, 0), BUCKETS - 1);
    }

    /**
     * @param histogram the merged counts, {@value #BUCKETS} entries
     * @param quantile 0 to 1, for example 0.95
     * @return the duration in milliseconds at the geometric middle of the bucket holding that quantile, or null when
     *     the histogram is empty
     */
    public static Long quantileMs(long[] histogram, double quantile) {
        if (histogram.length != BUCKETS) {
            throw new IllegalArgumentException("A duration histogram has " + BUCKETS + " buckets");
        }
        long total = 0;
        for (long count : histogram) {
            total += count;
        }
        if (total == 0) {
            return null;
        }
        long rank = Math.max(1, (long) Math.ceil(quantile * total));
        long seen = 0;
        for (int k = 0; k < BUCKETS; k++) {
            seen += histogram[k];
            if (seen >= rank) {
                return Math.round(FIRST_MS * Math.pow(2.0, (k + 0.5) / 2.0));
            }
        }
        return Math.round(FIRST_MS * Math.pow(2.0, (BUCKETS - 0.5) / 2.0));
    }
}
