package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * Money item type extension: an item definition whose values are money in one currency.
 *
 * @param currency currency
 */
public record MoneyType(
        String currency) implements HorusExtension {
}
