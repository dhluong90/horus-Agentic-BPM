package io.github.dhluong90.bpm.process.internal.model.di;

/**
 * DC {@code Font}.
 *
 * @param name name
 * @param size size
 * @param isBold isBold
 * @param isItalic isItalic
 * @param isUnderline isUnderline
 * @param isStrikeThrough isStrikeThrough
 */
public record Font(
        String name,
        Double size,
        boolean isBold,
        boolean isItalic,
        boolean isUnderline,
        boolean isStrikeThrough) {
}
