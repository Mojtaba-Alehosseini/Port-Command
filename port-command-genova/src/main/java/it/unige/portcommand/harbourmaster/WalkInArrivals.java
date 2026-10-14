package it.unige.portcommand.harbourmaster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

import it.unige.portcommand.ontology.VesselSpec;

/**
 * Which walk-in comes next, and what "Next ship" says when the port is full (S18b, playtest 4,
 * PT21). Pure: no agent, no clock, no Prolog - the caller hands in the test "does this ship fit a
 * berth that is free now", which in the game is the R8 search {@code find_compatible_berths}
 * minus the berths that are held or closed ({@code ForwardWalkInToPlayerBehaviour
 * .freeCompatibleBerths}). No new rule.
 *
 * <p>Before S18b the type was drawn uniformly over the five. Container ships and cruise ships fit
 * only berth 2 - two fifths of all arrivals - so with berth 2 taken they queued for hours while
 * berths 1 and 3 stood empty, and "Next ship" was refused with the port "full" of ships waiting
 * for the one berth.
 */
public final class WalkInArrivals {

    /** One sampled ship of a type: what would arrive if that type were drawn. */
    public record Candidate(String vesselType, double draft, double length, int tonnage) {
    }

    private WalkInArrivals() {
    }

    /**
     * The next arrival: drawn uniformly among the candidates that fit a berth that is free now,
     * and among all of them when none does (the port is full either way, and the ship waits as
     * it always has). One {@code draw} call.
     */
    public static Candidate choose(List<Candidate> candidates, Predicate<Candidate> fitsAFreeBerthNow,
                                   Random draw) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("no vessel types to draw from");
        }
        List<Candidate> fitting = candidates.stream().filter(fitsAFreeBerthNow).toList();
        List<Candidate> pool = fitting.isEmpty() ? candidates : fitting;
        return pool.get(draw.nextInt(pool.size()));
    }

    /**
     * The bracket in "Four walk-ins in port (2 waiting for berth 2) — wait for one to leave.":
     * the ships in the berth queue, grouped by the berths they could take, in queue order. Empty
     * when nobody is waiting.
     *
     * @param compatibleBerthsOfEachWaiting for each queued ship, its R8-compatible berths
     */
    public static String waitingNote(List<List<String>> compatibleBerthsOfEachWaiting) {
        Map<String, Integer> byBerths = new LinkedHashMap<>();
        for (List<String> berths : compatibleBerthsOfEachWaiting) {
            if (berths != null && !berths.isEmpty()) {
                byBerths.merge(berthsPhrase(berths), 1, Integer::sum);
            }
        }
        if (byBerths.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        byBerths.forEach((berths, count) -> parts.add(count + " waiting for " + berths));
        return "(" + String.join(", ", parts) + ")";
    }

    /**
     * S18b (adversarial review): the ships the refusal's bracket is about - the WALK-INS in the
     * berth queue, in queue order. A contracted ship can wait in the same queue, for the berth its
     * contract pins; it is not one of the four walk-ins, and R8's list for its type is not what it
     * waits for.
     *
     * @param queue  the berth queue's vessel ids, in arrival order
     * @param inPort the HarbourMaster's tracked vessels, by id
     */
    public static List<VesselSpec> waitingWalkIns(List<String> queue, Map<String, VesselTracking> inPort) {
        List<VesselSpec> walkIns = new ArrayList<>();
        for (String vesselId : queue) {
            VesselTracking tracked = inPort.get(vesselId);
            if (tracked != null && tracked.channel() == VesselTracking.Channel.WALK_IN) {
                walkIns.add(tracked.spec());
            }
        }
        return walkIns;
    }

    /** The whole "Next ship" refusal for a port that already holds the most walk-ins it takes. */
    public static String fullPortRefusal(List<List<String>> compatibleBerthsOfEachWaiting) {
        String note = waitingNote(compatibleBerthsOfEachWaiting);
        return "Four walk-ins in port" + (note.isEmpty() ? "" : " " + note) + " — wait for one to leave.";
    }

    /** {@code [berth_2]} reads "berth 2"; {@code [berth_1, berth_3]} reads "berth 1 or 3". */
    static String berthsPhrase(List<String> berthIds) {
        List<String> numbers = berthIds.stream().map(b -> b.replace("berth_", "")).sorted().toList();
        if (numbers.size() == 1) {
            return "berth " + numbers.get(0);
        }
        return "berth " + String.join(", ", numbers.subList(0, numbers.size() - 1))
                + " or " + numbers.get(numbers.size() - 1);
    }
}
