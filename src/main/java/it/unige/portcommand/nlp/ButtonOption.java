package it.unige.portcommand.nlp;

/** One clarification fallback choice: player-visible {@code label} mapped to a Rasa intent. */
public record ButtonOption(String label, String mappedIntent) {
}
