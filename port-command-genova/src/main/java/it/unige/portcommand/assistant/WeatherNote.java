package it.unige.portcommand.assistant;

import java.util.Optional;

import it.unige.portcommand.harbourmaster.MovementGate;

/**
 * What the Assistant adds to a Hint when the weather would keep THAT vessel from moving (S16,
 * 2026-10-13, decision B10): {@code ship will wait: wind 32 kn, sea state stormy [R15,R19]}.
 *
 * <p><b>Why it exists.</b> Until S16 a binding move was refused while the weather was unsafe for
 * the vessel's type, so the player found out by trying. Since S16 (decision B0a) the deal closes
 * in any weather and the ship then waits at the channel entry until the movement gate lets her
 * in. That is the better rule — the negotiation is about price and hours, not about the sky — but
 * it means a player can sign a tanker in a storm and watch a berth sit reserved and empty for
 * hours. The recommendation itself does not change (the algorithm prices the deal; the wait costs
 * nothing it models), so the Hint says so beside it.
 *
 * <p><b>Where it is shown (S17a, ADR-21).</b> In S16 it was a message of its own, published after
 * the Hint's template: {@code Note: in this weather she will wait outside after a deal — …}. A
 * Hint is a card now, and the note is the last part of the card's DETAILS LINE
 * ({@link AssistantWording#details}), next to the expected value and the berth check — one place
 * for everything the plain sentence leaves out.
 *
 * <p><b>Why it is never part of the Hint text.</b> A Hint is two-step: the plain sentence at once,
 * then — if it passes {@link HallucinationValidator} — the model's rewording, which REPLACES the
 * sentence in place. Text appended to the sentence would vanish with it; and a wind figure or a
 * rule id inside the model's text would fail the validator, whose whole contract is "no number
 * the recommendation did not contain". The details line is neither shown to the model nor
 * validated nor replaced — every word of this part comes from the gate's own verdict.
 *
 * <p><b>Prolog decides.</b> Whether the weather is unsafe for the type, and by which rules, is
 * {@link MovementGate#check} — the same call the HarbourMaster makes before it lets the ship
 * move, so the note and the hold on the Port table cannot disagree about a reading they share.
 * This class only words the answer. Pure and static: no agent, no bus, no clock.
 */
public final class WeatherNote {

    /** What comes before the gate's reason. */
    static final String DETAILS_LEAD = "ship will wait: ";

    private WeatherNote() {
    }

    /**
     * The weather part of the details line for a Hint about a vessel of {@code vesselType} under
     * {@code readingOrNull}, or empty when there is nothing to warn of: the weather is safe for
     * that type, or no reading has arrived yet. The second case is deliberate — "no reading"
     * closes the movement gate, but a guess is not a warning, and the Assistant never tells the
     * player something it does not know.
     *
     * @param vesselType    the ontology type of the vessel under negotiation ({@code tanker}, …)
     * @param readingOrNull the last weather reading heard, or {@code null} before the first
     */
    public static Optional<String> forDetails(String vesselType, MovementGate.Reading readingOrNull) {
        if (readingOrNull == null || vesselType == null || vesselType.isBlank()) {
            return Optional.empty();
        }
        MovementGate.Verdict verdict = MovementGate.check(vesselType, readingOrNull);
        // The reason already ends in the rule tag: "wind 32 kn, sea state stormy [R15,R19]".
        return verdict.safe() ? Optional.empty() : Optional.of(DETAILS_LEAD + verdict.reason());
    }
}
