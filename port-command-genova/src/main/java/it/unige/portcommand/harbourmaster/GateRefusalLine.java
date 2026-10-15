package it.unige.portcommand.harbourmaster;

import java.util.List;
import java.util.stream.Collectors;

/**
 * S19 (playtest 5, PT30): what the player reads when the Prolog gate refuses a move. Playtest 5:
 * "Command PROPOSE for WALKIN-1 refused: berth_4 is incompatible with a cargo_vessel [R7]" was
 * technical. The rule id stays - it is how the player finds the rule - and the rest is plain:
 * which berth, which kind of ship, that nothing was sent, and the berths that would have fitted.
 * Pure: the berths that fit come from the caller's own Prolog query (R8's
 * {@code find_compatible_berths}); the technical reason still rides on the typed refusal event and
 * in the log.
 */
public final class GateRefusalLine {

    private static final String BERTH_PREFIX = "berth_";

    private GateRefusalLine() {
    }

    /** R7: {@code Berth 4 can't take a cargo ship [R7] — not sent. Berths that fit: 3.} */
    public static String incompatibleBerth(String berthId, String vesselType, List<String> fittingBerths) {
        String fit = fittingBerths == null || fittingBerths.isEmpty()
                ? " No berth here fits it [R8]."
                : " Berths that fit: "
                        + fittingBerths.stream().map(GateRefusalLine::berthNumber).collect(Collectors.joining(", ")) + ".";
        return "Berth " + berthNumber(berthId) + " can't take " + shipWords(vesselType) + " [R7] — not sent." + fit;
    }

    /** R8: {@code No berth here can take a container ship [R8] — not sent.} */
    public static String noBerthFits(String vesselType) {
        return "No berth here can take " + shipWords(vesselType) + " [R8] — not sent.";
    }

    /** {@code berth_3} → {@code 3}; any other id as it is. */
    static String berthNumber(String berthId) {
        String id = String.valueOf(berthId);
        return id.startsWith(BERTH_PREFIX) ? id.substring(BERTH_PREFIX.length()) : id;
    }

    /** A vessel type in plain words, with its article: {@code a cargo ship}, {@code a tanker}. */
    static String shipWords(String vesselType) {
        String words = switch (vesselType == null ? "" : vesselType) {
            case "cargo_vessel" -> "cargo ship";
            case "container_vessel" -> "container ship";
            case "cruise_ship" -> "cruise ship";
            case "tanker" -> "tanker";
            case "ferry" -> "ferry";
            case "" -> "ship";
            default -> vesselType.replace('_', ' ');
        };
        return "a " + words;
    }
}
