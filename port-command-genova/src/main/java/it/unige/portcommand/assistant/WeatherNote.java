package it.unige.portcommand.assistant;

import java.util.Optional;

import it.unige.portcommand.harbourmaster.MovementGate;

/**
 * The one line the Assistant adds to a Hint when the weather would keep THAT vessel from moving
 * (S16, 2026-10-13, decision B10): {@code Note: in this weather she will wait outside after a
 * deal — wind 32 kn, sea state stormy [R15,R19].}
 *
 * <p><b>Why it exists.</b> Until S16 a binding move was refused while the weather was unsafe for
 * the vessel's type, so the player found out by trying. Since S16 (decision B0a) the deal closes
 * in any weather and the ship then waits at the channel entry until the movement gate lets her
 * in. That is the better rule — the negotiation is about price and hours, not about the sky — but
 * it means a player can sign a tanker in a storm and watch a berth sit reserved and empty for
 * hours. The recommendation itself does not change (the algorithm prices the deal; the wait costs
 * nothing it models), so the Hint says so in a line of its own.
 *
 * <p><b>Why a separate line and not part of the Hint text.</b> A Hint is two-step: the plain
 * template at once, then — if it passes {@link HallucinationValidator} — the model's rewording,
 * which REPLACES the template in place. Text appended to the template would vanish with it; and
 * a wind figure or a rule id inside the model's text would fail the validator, whose whole
 * contract is "no number the recommendation did not contain". The note is therefore published as
 * an ordinary Assistant message next to the template: appended, never replaced, never shown to
 * the model, never validated — every word of it comes from the gate's own verdict.
 *
 * <p><b>Prolog decides.</b> Whether the weather is unsafe for the type, and by which rules, is
 * {@link MovementGate#check} — the same call the HarbourMaster makes before it lets the ship
 * move, so the note and the hold on the Port table cannot disagree about a reading they share.
 * This class only words the answer. Pure and static: no agent, no bus, no clock.
 */
public final class WeatherNote {

    /** What comes before the gate's reason. "She": the vessel the Hint is about. */
    static final String LEAD = "Note: in this weather she will wait outside after a deal — ";

    private WeatherNote() {
    }

    /**
     * The note for a Hint about a vessel of {@code vesselType} under {@code readingOrNull}, or
     * empty when there is nothing to warn of: the weather is safe for that type, or no reading
     * has arrived yet. The second case is deliberate — "no reading" closes the movement gate, but
     * a guess is not a warning, and the Assistant never tells the player something it does not
     * know.
     *
     * @param vesselType    the ontology type of the vessel under negotiation ({@code tanker}, …)
     * @param readingOrNull the last weather reading heard, or {@code null} before the first
     */
    public static Optional<String> forHint(String vesselType, MovementGate.Reading readingOrNull) {
        if (readingOrNull == null || vesselType == null || vesselType.isBlank()) {
            return Optional.empty();
        }
        MovementGate.Verdict verdict = MovementGate.check(vesselType, readingOrNull);
        if (verdict.safe()) {
            return Optional.empty();
        }
        // The reason already ends in the rule tag: "wind 32 kn, sea state stormy [R15,R19]".
        return Optional.of(LEAD + verdict.reason() + ".");
    }
}
