package it.unige.portcommand.persistence;

/**
 * S18b (PLAYABILITY_DECISIONS §10): the manual save's refusal for an open negotiation, told apart
 * from every other refusal. The window can then ask "Open negotiations will be lost. Save
 * anyway?" and, on yes, save without those vessels — what the autosave has always done
 * ({@link GameSession#saveNow(boolean)}). The other refusals ("between phases", the ending day)
 * stay plain {@link SaveNotAllowedException}s: they pass in a moment and are not the player's to
 * waive.
 */
public final class OpenNegotiationException extends SaveNotAllowedException {

    public OpenNegotiationException(String message) {
        super(message);
    }
}
