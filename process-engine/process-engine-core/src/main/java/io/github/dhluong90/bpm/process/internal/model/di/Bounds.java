package io.github.dhluong90.bpm.process.internal.model.di;

/**
 * DC {@code Bounds}; diagram coordinates, never money.
 *
 * @param x x
 * @param y y
 * @param width width
 * @param height height
 */
public record Bounds(
        double x,
        double y,
        double width,
        double height) {
}
