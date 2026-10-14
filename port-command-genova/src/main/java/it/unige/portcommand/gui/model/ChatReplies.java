package it.unige.portcommand.gui.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What a chat tab answers when a typed line was read correctly and is not a move to send
 * (S18a, ADR-23; decisions N6, N7 and N8). Pure text, no widget toolkit import and no thread
 * assertion — {@code DialogueTabView} decides WHEN each applies, this class is what it says.
 *
 * <p>The three cases had one thing in common before S18a: the parse was right and the reply said
 * something else. A question about the port got this negotiation's status line; a limit the
 * grammar read correctly was called "fleet-wide"; and "help" pressed the Hint button, which
 * recommends a price. Each reply here says what was understood and where the answer is, and none
 * of them claims the game does more than it does.
 */
public final class ChatReplies {

    /** What a free berth's row says in the Port table ({@link PortTableModel#FREE}). */
    static final String FREE_BERTHS = "Free berths are in the Port table on the left — a berth's row "
            + "reads '" + PortTableModel.FREE + "' when nothing holds it.";

    static final String TUGS = "Tugs are in the Port table on the left — the "
            + PortTableModel.TUGS_LABEL + " line counts the free and the busy ones.";

    /** The weather has been in the top bar since S16 ({@link HudWeatherModel}); a click opens the forecast. */
    static final String WEATHER = "The weather is in the top bar — click it for the forecast.";

    /** Where policies are set — the same words the pipeline uses for a policy typed in chat. */
    static final String POLICIES = "Standing policies are set in Settings → Autopilot policy.";

    private ChatReplies() {
    }

    /**
     * Decision N6: a question about the PORT (the grammar's {@code free_berths},
     * {@code tug_availability} and {@code weather} topics) is answered with where the port's own
     * answer is on screen. The tab does not hold port state, and it does not pretend to: it
     * points at the panel that does.
     *
     * @param topic the {@code topic} element of an {@code ask} frame; anything else is no topic
     * @return the reply, or empty when the question is about this negotiation (or names no topic)
     *         — the caller then answers with the negotiation's own figures
     */
    public static Optional<String> portAnswer(Object topic) {
        if (!(topic instanceof String name)) {
            return Optional.empty();
        }
        return switch (name) {
            case "free_berths" -> Optional.of(FREE_BERTHS);
            case "tug_availability" -> Optional.of(TUGS);
            case "weather" -> Optional.of(WEATHER);
            default -> Optional.empty();
        };
    }

    /** The grammar's topic for "where is my berth" / "which berth do I get". */
    public static boolean asksForTheBerth(Object topic) {
        return "berth_assignment".equals(topic);
    }

    /**
     * Decision N6: "which berth do I get" — the berth and hours under negotiation.
     *
     * @param otherVessel the display name when the question was about another tab's vessel, or
     *                    {@code null} for the tab it was typed in
     */
    public static String berthAnswer(String otherVessel, String berthId, int durationHours) {
        String subject = otherVessel == null ? "This negotiation is"
                : "The negotiation with " + otherVessel + " is";
        String berth = berthId == null || berthId.isBlank() ? "a berth not yet named"
                : berthId.replace('_', ' ');
        return subject + " for " + berth + " for " + durationHours + "h — the Port table on the left "
                + "shows who holds each berth.";
    }

    /**
     * Decision N7: a limit, a port command or another instruction that was read correctly and that
     * this version does not carry out. It says which of the three it understood — a limit is not
     * "fleet-wide", which is what the single old line called all of them — that nothing was sent,
     * and what to type to actually move the negotiation.
     *
     * @param content the routed REQUEST's content: a DCG {@code constrain} frame, a DCG
     *                {@code command} frame, or Rasa's entities (possibly empty or {@code null})
     * @param example this tab's own worked example, or empty when it has none
     */
    public static String notExecuted(Map<String, Object> content, String example) {
        String understood;
        if (content != null && "constrain".equals(content.get("move"))) {
            understood = "Understood as a limit for this negotiation — but limits are not enforced";
        } else if (content != null && content.get("action") != null) {
            understood = "Understood as a fleet-wide command — but commands are not executed";
        } else {
            understood = "I read that as an instruction for the port — but instructions are not executed";
        }
        String whatWorks = example == null || example.isBlank() ? ""
                : " To move this negotiation write '" + example + "'.";
        return understood + " in this version, so nothing was sent." + whatWorks;
    }

    /**
     * Decision N8: the answer to a typed "help" — what this tab understands, in this tab's own
     * numbers. The offers are the tab's rotating examples (every one of them parses and sits above
     * the vessel's offer, see {@link InputExamples}) without the acceptance word, which gets its
     * own clause beside the refusal.
     *
     * @param examples      {@link InputExamples#examplesFor}; empty on a tab with nothing to offer on
     * @param hintAvailable whether the Hint button is on screen (it is not while the Assistant
     *                      negotiates in the tab)
     */
    public static String help(List<String> examples, boolean hintAvailable) {
        String offers = examples.stream()
                .filter(e -> !InputExamples.ACCEPT_UTTERANCE.equals(e))
                .map(e -> "'" + e + "'")
                .collect(Collectors.joining(", "));
        String offer = offers.isEmpty() ? "an offer (a price and hours)" : "an offer (" + offers + ")";
        String hint = hintAvailable ? "; Hint asks the Assistant for a suggestion" : "";
        return "You can type " + offer + ", '" + InputExamples.ACCEPT_UTTERANCE + "' or '"
                + InputExamples.REJECT_UTTERANCE + "', or 'status'. Accept and Reject send '"
                + InputExamples.ACCEPT_UTTERANCE + "' and '" + InputExamples.REJECT_UTTERANCE + "'" + hint
                + ". " + POLICIES;
    }
}
