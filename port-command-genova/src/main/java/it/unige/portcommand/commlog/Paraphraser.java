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
            case ACLMessage.ACCEPT_PROPOSAL -> "Granted " + deal(content);
            case ACLMessage.REJECT_PROPOSAL -> "Declined the offer" + reasonSuffix(content);
            case ACLMessage.CFP -> "Escort needed for " + vessel(content);
            case ACLMessage.CONFIRM -> "Cleared " + clearance(content);
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
            return "berth " + berthId + " for " + vesselId;
        }
        if (vesselId != null) {
            return "action for " + vesselId;
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
            String vesselId = vesselIdOf(content);
            return "€" + money(price) + (vesselId != null ? " to " + vesselId : "");
        }
        return "terms";
    }

    /**
     * ACCEPT_PROPOSAL shapes: {@code {berth_id, price, hours}} (HM grants a berth — both
     * contracted and walk-in), {@code {intent:"accept", price, hours}} (vessel accepts a
     * counter), {@code {vessel_id, berth_position}} (CNP award to the winning tug — no price),
     * or an empty/arbitrary player-command map.
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
            return "the escort contract for " + text(content, "vessel_id");
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
        return v != null ? v : "a vessel";
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
     * weather_threshold/weather_clear/weather_resume), or {@code notice} (handling_complete)
     * — plus the routine weather
     * broadcast, which has none of the three. {@code priority=high} is an ACL envelope
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
                case "weather_hold" -> "weather hold (wind " + moneyText(content, "wind_knots") + "kn)";
                case "weather_threshold" ->
                        "weather alert (wind " + moneyText(content, "wind_knots") + "kn, " + text(content, "state") + ")";
                // Task 24: the recovery-direction signals (weather_clear = agent→HM crossing
                // alert; weather_resume = HM→vessel re-dispatch notice). Deliberately calm
                // wording — only worsening alerts carry priority=high/URGENT.
                case "weather_clear" ->
                        "weather clearing (wind " + moneyText(content, "wind_knots") + "kn, " + text(content, "state") + ")";
                case "weather_resume" -> "weather cleared — resuming escort for " + orVessel(content);
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
        return v != null ? v : "a vessel";
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
