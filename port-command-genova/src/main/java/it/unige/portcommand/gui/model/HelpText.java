package it.unige.portcommand.gui.model;

import java.util.List;

/**
 * What Help says (S18b, REMAINING_AREAS_DECISIONS P7: "rewrite Help for the current game"). Pure
 * text, no widget toolkit import; {@code HelpDialog} lays it out.
 *
 * <p>The pre-S18b Help listed the commands of the task-19 chat — before the three-column window,
 * the Finished list, the top bar's pause button, Hint's "Use this", the policies and the phrasings
 * S18a taught the grammar. It also quoted literal prices ("6500"), which copied onto a vessel
 * already offering more would close that deal far too cheaply — the reason the input's examples
 * are computed per tab (S15). Here a typed move is a pattern: {@link #PRICE}, {@link #HOURS} and
 * {@link #NAME} stand for the player's own numbers and a vessel's name. {@code HelpTextDcgTest}
 * fills them in and runs every alternative of every move row through the real grammar.
 *
 * <p>Nothing here labels or explains the time a vessel will still wait (INVARIANTS).
 */
public final class HelpText {

    /** Stands for the player's price in a typed pattern. */
    public static final String PRICE = "<price>";
    /** Stands for the player's hours in a typed pattern. */
    public static final String HOURS = "<hours>";
    /** Stands for another tab's vessel name in a typed pattern. */
    public static final String NAME = "<name>";
    /** Between two alternatives that mean the same in one row. */
    public static final String OR = "  ·  ";

    /**
     * One line of Help.
     *
     * @param key     what to type, press or look at
     * @param meaning what it does, in plain words
     * @param move    for a typed negotiation move, the move the grammar reads every alternative
     *                as ({@code counter}, {@code accept}, {@code reject}, {@code ask}); {@code null}
     *                for anything that is not one
     */
    public record Row(String key, String meaning, String move) {

        public static Row of(String key, String meaning) {
            return new Row(key, meaning, null);
        }
    }

    /**
     * @param typed whether the keys are things to TYPE (shown in a fixed-width font)
     */
    public record Section(String title, String intro, boolean typed, List<Row> rows) {
    }

    private HelpText() {
    }

    public static List<Section> sections() {
        return List.of(
                new Section("How to play", "You are the Harbour Master of Genoa.", false, List.of(
                        Row.of("Walk-in ships", "hail you and ask for a berth — each gets a chat tab. You sell the "
                                + "berth time: they pay you."),
                        Row.of("Contracted ships", "arrive on their schedule; the Port table shows them."),
                        Row.of("Midnight", "ends the day: the daily costs (€5,200) are charged and the day is "
                                + "scored. A scenario ends at its first midnight."))),
                new Section("The window", null, false, List.of(
                        Row.of("Top bar", "day and time, wallet, reputation, today's profit against the target, "
                                + "and the score (point at it for its four parts)"),
                        Row.of("⏸ / ▶ Resume   ×1 ×2 ×4", "pause or resume (PAUSED shows beside it), and the "
                                + "game speed"),
                        Row.of("Next ship", "calls the next walk-in now"),
                        Row.of("Weather", "the last reading — click it for the forecast"),
                        Row.of("Waiting for you", "the ships waiting for your answer — click one to open its tab"),
                        Row.of("Port", "the four berths, the queue and the tugs"),
                        Row.of("Finished", "every negotiation that ended in this game — click one to read it again"),
                        Row.of("Comm log", "the HarbourMaster's messages: All, This vessel, Rules, Weather. Raw shows "
                                + "the FIPA fields; click a rule id such as [R7] to read the rule; right-click to "
                                + "copy; Export… saves it as a text file; ▼ Jump to latest goes back to the newest "
                                + "message."))),
                // S19 (playtest 5, PT34): "the comm log is hard to read (berth/tug part)".
                new Section("How to read the comm log",
                        "The life of one ship, in six steps - the messages it takes, in the order they come:",
                        false, List.of(
                        Row.of("1  Deal", "the ship hails and offers (PROPOSE); you agree a price in its chat tab "
                                + "(ACCEPT-PROPOSAL)"),
                        Row.of("2  Berth", "the HarbourMaster gives it a berth that fits (ACCEPT-PROPOSAL, [R7]) - "
                                + "or queues it until one is free"),
                        Row.of("3  Customs", "the ship is cleared, or inspected first ([R21]-[R24])"),
                        Row.of("4  Tug auction", "the HarbourMaster calls for tugs (CFP); each free tug bids "
                                + "(PROPOSE); the best bid wins (ACCEPT-PROPOSAL, [R12]) and the others are turned "
                                + "down (REJECT-PROPOSAL)"),
                        Row.of("5  Escort and dock", "the tugs bring the ship to its berth; bad weather holds it "
                                + "outside until it clears ([R15]-[R20])"),
                        Row.of("6  Departure", "its hours over, the ship leaves; the tugs that served it report "
                                + "free"),
                        Row.of("Help ▸ Rules (30)…", "all 30 Prolog rules in their five groups, each with its file "
                                + "and how often this game cited it"),
                        Row.of("Tip", "press This vessel above the log and pick the ship's chat tab: the log "
                                + "shows only that ship's messages"))),
                new Section("Talking to a ship",
                        "Type in the ship's chat tab, or press its buttons: Accept takes their offer, Reject turns "
                                + "them away, Hint asks the Assistant. The grey example in the box is a price that "
                                + "fits that ship.",
                        true, List.of(
                        new Row(PRICE + " for " + HOURS + "h", "your price and the hours you sell", "counter"),
                        new Row(PRICE + " for " + HOURS + " hours", "the same, written out", "counter"),
                        new Row("make it " + PRICE + OR + "can you do " + PRICE + OR + "I want " + PRICE,
                                "a new price — the hours stay as they are", "counter"),
                        new Row("make it " + PRICE + " and " + HOURS + " hours", "a new price and new hours", "counter"),
                        new Row("what about " + HOURS + " hours" + OR + "make it " + HOURS + " hours",
                                "new hours — your price stays", "counter"),
                        new Row("200 more" + OR + "10 percent higher",
                                "up from your last price (before you have named one, from theirs)", "counter"),
                        new Row("split the difference", "halfway between your price and theirs", "counter"),
                        new Row("deal" + OR + "yes" + OR + "yeah" + OR + "sure" + OR + "let's do it",
                                "take their offer — what the Accept button sends", "accept"),
                        new Row("no" + OR + "reject",
                                "turn them away — what the Reject button sends; it ends the negotiation", "reject"),
                        // Answered by the chat itself before any parse (S18a), not a grammar move.
                        Row.of("status", "their offer, your offer and the round"),
                        new Row("which berths are free?", "where to look — the Port table", "ask"),
                        new Row(NAME + ": " + PRICE + " for " + HOURS + "h",
                                "any of these, to the ship of another tab, by its name", "counter"),
                        Row.of("help", "what you can type in that tab"))),
                new Section("The Assistant", null, false, List.of(
                        Row.of("Hint", "a recommended move for this ship, with its figures — Use this sends it"),
                        Row.of("Autopilot", "Settings ▸ Autopilot: the Assistant negotiates for you; Take over "
                                + "hands a tab back to you"),
                        Row.of("accept if price > 1500", "an autopilot policy, set in Settings ▸ Autopilot policy: "
                                + "accept, reject or counter +N% if price, duration or rounds_used compares to a "
                                + "number, optionally 'for tankers' (or another type)"),
                        Row.of("Clear policies", "forgets every policy — after asking"))),
                new Section("Keys", null, false, List.of(
                        Row.of("Esc", "pause / resume"),
                        Row.of("Ctrl+1" + OR + "Ctrl+2" + OR + "Ctrl+4", "game speed ×1, ×2, ×4"),
                        Row.of("Enter", "sends what you typed"))),
                new Section("Saving", null, false, List.of(
                        Row.of("Autosave", "at the end of every day — Game ▸ Load Game… ▸ Autosave"),
                        Row.of("Game ▸ Save Game…", "your one manual save; negotiations still open are not "
                                + "saved (you are asked first)"),
                        Row.of("Game ▸ Export comm log…", "the comm log as you see it, as a text file you choose "
                                + "- it ends with the rules this game cited (the full archive stays on disk, under "
                                + "logs/)"),
                        Row.of("Exit", "asks first while a game is running: Save & quit, Quit or Cancel"))));
    }
}
