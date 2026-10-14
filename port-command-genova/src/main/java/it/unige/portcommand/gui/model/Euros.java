package it.unige.portcommand.gui.model;

import java.util.Locale;

/**
 * S18b (playtest 2, PT3): how the chat column writes an amount — whole euros with a thousands
 * comma, {@code €1,460}, the way the top bar and the end-of-day summary already write the wallet.
 * Until S18b the chat wrote {@code €1460} under a top bar that wrote {@code €15,000}.
 *
 * <p>An amount copied from the chat back into the input still reads right: the grammar's tokeniser
 * drops a thousands comma ({@code 2,000} is 2000). What the player is asked to TYPE — the input's
 * examples and the "write '2000 for 6h'" lines — stays without one, as typed numbers usually are.
 */
public final class Euros {

    private Euros() {
    }

    /** {@code €1,460}, or {@code −€1,183} below zero. {@link Locale#ROOT}, so the digits and the
     * comma are the same on every machine. */
    public static String of(double amount) {
        long whole = Math.round(amount);
        return (whole < 0 ? "−" : "") + String.format(Locale.ROOT, "€%,d", Math.abs(whole));
    }
}
