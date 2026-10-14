package it.unige.portcommand.commlog;

import com.fasterxml.jackson.databind.JsonNode;
import it.unige.portcommand.core.TerminalJson;
import jade.lang.acl.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts an {@link ACLMessage} to a one-line natural-language string for the
 * comm log (task 18/19 render it, coloured by {@link PerformativeColours}).
 * Purely deterministic — never calls the LLM. Exactly 10 templates, one per
 * canonical FIPA performative (PROJECT_DEFINITION §6.1 / §7.4): REQUEST,
 * PROPOSE, ACCEPT_PROPOSAL, REJECT_PROPOSAL, CFP, CONFIRM, INFORM, REFUSE,
 * CANCEL, DISCONFIRM. Content shapes are grounded in every real
 * {@code MessageFactory} call site in this codebase (auto-flow, walk-in
 * negotiation, CNP, weather, customs, player commands, NLP/DCG routing) — see
 * each helper's javadoc for the shapes it handles.
 *
 * <p><b>Never throws.</b> A performative outside the 10 (e.g. {@code QUERY_REF},
 * which the player's "ASK" command and the NLP {@code query_status}/{@code ask}
 * paths really do send), a {@code null}/empty content body (the CNP loser's
 * REJECT_PROPOSAL carries none), or malformed JSON all fall through to a safe
 * generic fallback instead of a template match or an exception.
 */
public final class Paraphraser {

    private static final Logger log = LoggerFactory.getLogger(Paraphraser.class);

    /**
     * S8 (GOAL 3): vessel-id → display-string hook, wired by the HarbourMaster from its live
     * tracking ("Stella Maris (WALKIN-3)" for named vessels, the bare id otherwise) and reset
     * at its takedown — the same set-at-setup/clear-at-teardown discipline as the task-22 bus
     * subscriptions, so a reloaded world never renders through a dead HM's roster. Identity by
     * default: unit tests that call the paraphraser directly are byte-for-byte unchanged.
     * Bound, stated: only vessels the CURRENT HarbourMaster tracks resolve — departed vessels'
     * later mentions (and pre-wiring boot lines) fall back to the id. So does a walk-in's FIRST
     * contact (S12, live playtest F-5): its opening PROPOSE is logged inside {@code receiveLogged},
     * before {@code handleOffer} creates the tracking this resolver reads — which is why
     * {@link #offerer} names a PROPOSE from the message's own {@code vessel_spec} first.
     */
    private static volatile java.util.function.UnaryOperator<String> vesselDisplay =
            java.util.function.UnaryOperator.identity();
    private static volatile Object vesselDisplayOwner;

    /**
     * Install the id → display resolver (HarbourMaster setup). {@code owner} identifies the
     * installer so a LATE asynchronous takedown of an outgoing world cannot wipe the incoming
     * world's resolver (S8 adversarial finding 2 — JADE {@code doDelete} is async, and the
     * failure would be invisible: the comm log would silently fall back to bare ids).
     */
    public static synchronized void setVesselDisplay(Object owner,
            java.util.function.UnaryOperator<String> resolver) {
        // synchronized since S10 (bug hunt, finding C7): the compare-and-clear below was a
        // non-atomic check-then-act — a clear's owner read could interleave before this
        // method's stores and its resets after, wiping the incoming world's hook in a
        // ~2-instruction window. The class lock makes owner-check + both stores one step.
        vesselDisplayOwner = owner;
        vesselDisplay = resolver == null ? java.util.function.UnaryOperator.identity() : resolver;
    }

    /** Reset to identity ONLY if {@code owner} still owns the hook — atomic with the check (S10). */
    public static synchronized void clearVesselDisplay(Object owner) {
        if (vesselDisplayOwner == owner) {
            vesselDisplayOwner = null;
            vesselDisplay = java.util.function.UnaryOperator.identity();
        }
    }

    /** The display string for a vessel id (never null; identity when no resolver is wired). */
    private static String display(String vesselId) {
        if (vesselId == null) {
            return null;
        }
        String rendered = vesselDisplay.apply(vesselId);
        return rendered == null ? vesselId : rendered;
    }

    /** The HarbourMaster's DF/local name — the sender whose PROPOSE is a demand, not an offer. */
    private static final String HARBOUR_MASTER = "harbour_master";

    private Paraphraser() {
    }

    /**
     * Sender-agnostic overload — derives the sender from the message's own {@code getSender()}
     * (null when no live AID is attached, e.g. in unit tests). The comm-log path should prefer
     * the {@link #paraphrase(ACLMessage, String)} overload with the already-resolved local name.
     */
    public static String paraphrase(ACLMessage msg) {
        String sender = msg == null || msg.getSender() == null ? null : msg.getSender().getLocalName();
        return paraphrase(msg, sender);
    }

    /**
     * @param senderLocalName the message's sender local name (the comm-log call site already
     *                        resolves it); load-bearing ONLY for PROPOSE, where a HarbourMaster
     *                        PROPOSE is a price DEMAND ("Demanding €X") while a vessel's own
     *                        offer or a tug's CNP escort bid stays "Offering" (checkpoint #5–#7,
     *                        2026-07-18). Paraphrase layer only — the ACL performative is
     *                        untouched.
     */
    public static String paraphrase(ACLMessage msg, String senderLocalName) {
        if (msg == null) {
            return "(no message)";
        }
        try {
            return paraphraseInternal(msg, senderLocalName);
        } catch (RuntimeException e) {
            log.warn("Paraphraser: falling back to a generic rendering for an unparseable message", e);
            return genericFallback(msg);
        }
    }

    private static String paraphraseInternal(ACLMessage msg, String senderLocalName) {
        JsonNode content = TerminalJson.readTreeOrNull(msg.getContent());
        return switch (msg.getPerformative()) {
            case ACLMessage.REQUEST -> "Requesting " + requestObject(content);
            case ACLMessage.PROPOSE -> proposeVerb(senderLocalName) + offer(content);
            // S12 (live playtest F-4): every HM berth grant carries the rule that decided it —
            // "(compatible [R7])" contracted, "(compatible [R8])" walk-in, or the wait-queue
            // release rationale "(after queue scheduled ferry rank 3 [R25,R26,R27,R30])". The
            // PRODUCER names the rule; this template only renders it, because it cannot tell a
            // grant from a forwarded player ACCEPT nor know whether R7 or R8 decided.
            case ACLMessage.ACCEPT_PROPOSAL -> "Granted " + deal(content) + reasonSuffix(content);
            case ACLMessage.REJECT_PROPOSAL -> "Declined the offer" + reasonSuffix(content);
            case ACLMessage.CFP -> "Escort needed for " + vessel(content);
            // reasonSuffix renders the S5 customs rationale ("(inspected [R22,R24])"); empty on
            // every pre-S5 CONFIRM shape, including the walk-in deal_confirmed.
            // S18b (UI tree U15): a customs clearance read "Cleared clearance CL-…".
            case ACLMessage.CONFIRM -> (text(content, "ref") != null
                    ? "Cleared by customs, ref " + text(content, "ref")
                    : "Cleared " + clearance(content)) + reasonSuffix(content);
            // S18b (U15): the player's status question (an ASK sent through the HarbourMaster)
            // used to fall to the generic "Sent a QUERY-REF message".
            case ACLMessage.QUERY_REF -> "Asking for the vessel's status";
            case ACLMessage.INFORM -> "Notifying: " + notice(content, msg);
            case ACLMessage.REFUSE -> "Refusing" + reasonSuffix(content);
            case ACLMessage.CANCEL -> "Cancelling" + reasonSuffix(content);
            case ACLMessage.DISCONFIRM -> "Retracting " + retraction(content);
            default -> genericFallback(msg);
        };
    }

    private static String genericFallback(ACLMessage msg) {
        String name;
        try {
            name = ACLMessage.getPerformative(msg.getPerformative());
        } catch (RuntimeException e) {
            name = "message";
        }
        return "Sent a " + name + " message";
    }

    /**
     * REQUEST shapes: {@code {intent:"request_berth", contract, vessel_spec:{vessel_id}}}
     * (vessel→HM), a 9-field {@code BerthRequest} (HM→terminal, top-level {@code vessel_id}
     * + {@code berth_id}), {@code {vessel_id, vessel_type, cargo_class}} (HM→customs), or an
     * NLP/DCG-routed flat entity map with no recognisable vessel id at all.
     */
    private static String requestObject(JsonNode content) {
        if (content == null) {
            return "assistance";
        }
        String vesselId = vesselIdOf(content);
        String berthId = text(content, "berth_id");
        if (vesselId != null && berthId != null) {
            return "berth " + berthId + " for " + display(vesselId);
        }
        if (vesselId != null) {
            return "action for " + display(vesselId);
        }
        return "assistance";
    }

    /**
     * PROPOSE verb (#6): the HarbourMaster PROPOSEs a price to a walk-in as a DEMAND
     * ("Demanding €X"); a vessel PROPOSEs its own offer and a tug PROPOSEs a CNP escort bid,
     * both of which stay "Offering". A null sender (no live AID — unit tests) reads as an offer.
     */
    private static String proposeVerb(String senderLocalName) {
        return HARBOUR_MASTER.equals(senderLocalName) ? "Demanding " : "Offering ";
    }

    /**
     * PROPOSE shapes: {@code {intent:"opening_offer"|"counter_offer", price, hours, vessel_spec}}
     * (walk-in negotiation), a tug CNP bid {@code {cost, eta_minutes, fuel_state, position}}
     * (no price/vessel id at all), or a player-command/DCG-frame map that may carry only a
     * bare {@code price}.
     *
     * <p>S12 (live playtest F-5): a priced offer reads {@code "€P for Hh from <offerer>"}. The
     * clause used to be {@code " to " + id}, but the only PROPOSE that carries a vessel id is the
     * vessel's OWN offer, so "to" named the sender as the recipient. The HarbourMaster's PROPOSE
     * carries no vessel id and renders with no clause at all ("Demanding €1900 for 6h").
     */
    private static String offer(JsonNode content) {
        if (content == null) {
            return "terms";
        }
        if (content.has("cost") && content.has("eta_minutes")) {
            return "escort bid: €" + moneyText(content, "cost") + " (ETA " + moneyText(content, "eta_minutes") + "m)";
        }
        Double price = numOrNull(content, "price");
        if (price != null) {
            Double hours = numOrNull(content, "hours");
            String offerer = offerer(content);
            return "€" + money(price)
                    + (hours != null ? " for " + money(hours) + "h" : "")
                    + (offerer != null ? " from " + offerer : "");
        }
        return "terms";
    }

    /**
     * Who made a priced PROPOSE, or {@code null} when the content carries no vessel id (the
     * HarbourMaster's own PROPOSE). {@code vessel_spec} is only ever attached by the vessel itself
     * ({@code OpeningProposalBehaviour}, {@code EvaluateCounterOfferBehaviour}), so it names the
     * OFFERER, never a recipient (S12, live playtest F-5). The name is read from the message's
     * own spec first: the opening PROPOSE is logged inside {@code receiveLogged} BEFORE
     * {@code handleOffer} creates the tracking the S8 resolver reads, so {@link #display} alone
     * renders the bare id on exactly that line. Same {@code "Name (ID)"} form the HarbourMaster's
     * resolver produces, so a vessel reads identically before and after its tracking exists.
     */
    private static String offerer(JsonNode content) {
        String id = vesselIdOf(content);
        if (id == null) {
            return null;
        }
        String name = text(content.get("vessel_spec"), "vessel_name");
        return name != null ? name + " (" + id + ")" : display(id);
    }

    /**
     * ACCEPT_PROPOSAL shapes: {@code {berth_id, price, hours, reason}} (HM grants a berth — both
     * contracted and walk-in; since S12 {@code reason} always names the deciding rule, rendered
     * by {@code reasonSuffix}, never here), {@code {intent:"accept", price, hours}} (vessel accepts
     * a counter), {@code {vessel_id, berth_position}} (CNP award to the winning tug — no price),
     * or an empty/arbitrary player-command map — which can carry a Rasa-backfilled
     * {@code berth_id} and so also reads "berth X", with no rule id, because no rule decided it.
     */
    private static String deal(JsonNode content) {
        if (content == null) {
            return "the request";
        }
        String berthId = text(content, "berth_id");
        if (berthId != null) {
            return "berth " + berthId;
        }
        if (content.has("vessel_id") && content.has("berth_position")) {
            return "the escort contract for " + display(text(content, "vessel_id"));
        }
        Double price = numOrNull(content, "price");
        if (price != null) {
            return "at €" + money(price);
        }
        return "the request";
    }

    /** CFP shape: {@code {target_vessel_id, target_vessel_position, reply_by}} — one producer, one shape. */
    private static String vessel(JsonNode content) {
        String v = text(content, "target_vessel_id");
        return v != null ? display(v) : "a vessel";
    }

    /**
     * CONFIRM shapes: {@code {intent:"deal_confirmed", price, hours}} (vessel confirms),
     * {@code {berth_id, crane_assigned}} (terminal confirms), or {@code {ref:"CL-2026-N"}}
     * (customs clearance).
     */
    private static String clearance(JsonNode content) {
        if (content == null) {
            return "the request";
        }
        String ref = text(content, "ref");
        if (ref != null) {
            return "clearance " + ref;
        }
        String berthId = text(content, "berth_id");
        if (berthId != null) {
            return "berth " + berthId + " assignment";
        }
        Double price = numOrNull(content, "price");
        if (price != null) {
            return "the deal at €" + money(price);
        }
        return "the request";
    }

    /**
     * INFORM shapes discriminate on whichever of three top-level keys is present —
     * {@code intent} (withdraw/departed/service_complete — vessel-originated),
     * {@code event} (tug_hold/escort_complete/flagged/routine_inspection/weather_hold/
     * weather_threshold/weather_clear/weather_resume/weather_periodic), or {@code notice}
     * (handling_complete) — plus a keys-only conditions shape carrying none of the three, which
     * is the pre-P1-4 weather broadcast and has had no producer since that broadcast gained its
     * {@code weather_periodic} discriminator. {@code priority=high} is an ACL envelope
     * parameter (weather threshold + customs flagged), never JSON content, so it is read off
     * {@code msg} directly.
     */
    private static String notice(JsonNode content, ACLMessage msg) {
        String prefix = "high".equals(msg.getUserDefinedParameter("priority")) ? "URGENT " : "";
        if (content == null) {
            return prefix + "an update";
        }
        String intent = text(content, "intent");
        if (intent != null) {
            return prefix + switch (intent) {
                case "withdraw" -> "withdrawal" + reasonSuffix(content);
                case "departed" -> orVessel(content) + " has departed";
                case "service_complete" -> "service complete for " + orVessel(content);
                // S16 (ADR-20): the ship reports its own arrival at the berth.
                case "docked" -> orVessel(content) + " docked at " + berthText(content);
                // S18b (U15): the vessel's answer to a status question, with its figures (all
                // its own and on the wire: its offer, its stay, the rounds run - P-04).
                case "status_report" -> "status of " + orVessel(content) + ": offer €"
                        + moneyText(content, "price") + " for " + intText(content, "hours")
                        + "h after " + intText(content, "rounds_used") + " round(s)";
                default -> humanize(intent);
            };
        }
        String event = text(content, "event");
        if (event != null) {
            return prefix + switch (event) {
                case "tug_hold" -> "tug hold" + reasonSuffix(content);
                case "escort_complete" -> "escort complete for " + orVessel(content);
                case "flagged" -> "customs flag" + reasonSuffix(content);
                case "routine_inspection" -> "routine inspection";
                // S16 (ADR-20): the hold says why - the gate's verdict with its rule ids - when the
                // message carries one; the bare wind figure is the pre-S16 shape.
                case "weather_hold" -> text(content, "reason") != null
                        ? "weather hold: " + text(content, "reason")
                        : "weather hold (wind " + moneyText(content, "wind_knots") + "kn)";
                // S16: the movement orders and the reports they wait for. A ship moves only on
                // proceed_to_berth, which goes out with its escort's begin_escort once every tug
                // has reported tug_on_station, customs has cleared it and the weather allows.
                case "proceed_to_berth" -> "proceed to " + berthText(content) + " for " + orVessel(content)
                        + escortSuffix(content);
                case "begin_escort" -> "begin the escort of " + orVessel(content);
                case "tug_on_station" -> "on station at " + orVessel(content);
                case "tug_available" -> "tug available";
                case "cleared_to_depart" -> "cleared to depart: " + orVessel(content);
                case "inspection_started" -> "customs inspection started" + reasonSuffix(content);
                case "turned_away" -> orVessel(content) + " turned away" + reasonSuffix(content);
                // S18b (U15): the scripted flood used to read "flood", without the berth.
                case "flood" -> "flood at " + berthText(content);
                case "weather_threshold" ->
                        "weather alert (wind " + moneyText(content, "wind_knots") + "kn, " + text(content, "state") + ")"
                                + reasonSuffix(content);
                // Task 24: the recovery-direction signals (weather_clear = agent→HM crossing
                // alert; weather_resume = HM→vessel re-dispatch notice). Deliberately calm
                // wording — only worsening alerts carry priority=high/URGENT.
                case "weather_clear" ->
                        "weather clearing (wind " + moneyText(content, "wind_knots") + "kn, " + text(content, "state") + ")"
                                + reasonSuffix(content);
                // reasonSuffix renders the S5 emergency-partition rationale ("[R29]") on the
                // storm-escalated re-dispatches; empty on ordinary resumes.
                case "weather_resume" ->
                        "weather cleared — resuming escort for " + orVessel(content) + reasonSuffix(content);
                // Audit P1-4 follow-up: the 15-sim-minute snapshot the WeatherAgent now sends the
                // hub. Without its own case it fell to humanize() below and read "weather
                // periodic" roughly 96 times a game day — the single noisiest line in the comm
                // log. Worded as an observation, not an alert: it reports conditions whether they
                // are good or bad, carries no priority=high, and unlike weather_threshold it
                // signals no transition.
                case "weather_periodic" ->
                        "weather check (wind " + moneyText(content, "wind_knots") + "kn, " + text(content, "state") + ")";
                default -> humanize(event);
            };
        }
        String notice = text(content, "notice");
        if (notice != null) {
            return prefix + switch (notice) {
                case "handling_complete" -> "cargo handling complete at berth " + text(content, "berth");
                default -> humanize(notice);
            };
        }
        if (content.has("wind_knots") && content.has("state")) {
            return prefix + "conditions: wind " + moneyText(content, "wind_knots") + "kn, " + text(content, "state");
        }
        return prefix + "an update";
    }

    /** S18b (U15): a whole number off the content as text, or "?" when absent. */
    private static String intText(JsonNode content, String field) {
        Double value = numOrNull(content, field);
        return value == null ? "?" : String.valueOf(Math.round(value));
    }

    /** {@code berth 1} for a {@code berth_id} of {@code berth_1}; "its berth" when absent. */
    private static String berthText(JsonNode content) {
        String berthId = text(content, "berth_id");
        return berthId == null || berthId.isEmpty() ? "its berth" : berthId.replace('_', ' ');
    }

    /** {@code  (escort of 2 on station)} for a proceed order; nothing for a ship that needs no tug. */
    private static String escortSuffix(JsonNode content) {
        Double escort = numOrNull(content, "escort");
        return escort == null || escort < 1 ? "" : " (escort of " + escort.intValue() + " on station)";
    }

    /** Every REFUSE/CANCEL/withdraw/tug_hold/flagged shape carries a plain {@code reason} string. */
    private static String reasonSuffix(JsonNode content) {
        if (content == null) {
            return "";
        }
        String reason = text(content, "reason");
        return reason != null ? " (" + humanize(reason) + ")" : "";
    }

    /** DISCONFIRM shape: {@code {notice:"retracted", berth_id}} — one producer, one shape. */
    private static String retraction(JsonNode content) {
        String berthId = text(content, "berth_id");
        return berthId != null ? "berth " + berthId + " confirmation" : "the assignment";
    }

    private static String vesselIdOf(JsonNode content) {
        String top = text(content, "vessel_id");
        if (top != null) {
            return top;
        }
        JsonNode spec = content.get("vessel_spec");
        return spec != null ? text(spec, "vessel_id") : null;
    }

    private static String orVessel(JsonNode content) {
        String v = vesselIdOf(content);
        return v != null ? display(v) : "a vessel";
    }

    private static String humanize(String s) {
        return s.replace('_', ' ');
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Double numOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }

    /** Formats a numeric field for display, dropping a trailing {@code .0} for whole numbers. */
    private static String moneyText(JsonNode node, String field) {
        Double v = numOrNull(node, field);
        return v == null ? "?" : money(v);
    }

    private static String money(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }
}
