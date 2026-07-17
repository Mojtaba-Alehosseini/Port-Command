package it.unige.portcommand.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import it.unige.portcommand.assistant.WalkInDialogueSnapshot;
import it.unige.portcommand.ontology.Deal;

/**
 * Pure rendering model for one walk-in dialogue's chat tab. No Swing/AWT import — the
 * transcript, current offer/round state, and closed/outcome flag live here;
 * {@code DialogueTabView} is the thin Swing rendering of it. Reads ONLY
 * {@link WalkInDialogueSnapshot}'s observable fields (P-04 — see
 * {@code NegotiationTabRegistryTest}'s grep gate, which covers this file too).
 */
public final class DialogueTabModel {

    public enum Speaker { PLAYER, VESSEL, ASSISTANT, SYSTEM }

    public record ChatEntry(Speaker speaker, String text) {
        public ChatEntry {
            Objects.requireNonNull(speaker, "speaker");
            Objects.requireNonNull(text, "text");
        }
    }

    private final String dialogueId;
    private final List<ChatEntry> transcript = new ArrayList<>();
    private WalkInDialogueSnapshot snapshot;
    private boolean closed;
    private Deal.Outcome outcome;

    public DialogueTabModel(String dialogueId, WalkInDialogueSnapshot snapshot) {
        this.dialogueId = Objects.requireNonNull(dialogueId, "dialogueId");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public String dialogueId() {
        return dialogueId;
    }

    public String vesselId() {
        return snapshot.vesselId();
    }

    public WalkInDialogueSnapshot snapshot() {
        return snapshot;
    }

    public void updateSnapshot(WalkInDialogueSnapshot newSnapshot) {
        this.snapshot = Objects.requireNonNull(newSnapshot, "newSnapshot");
    }

    public List<ChatEntry> transcript() {
        return List.copyOf(transcript);
    }

    public void appendVesselMessage(String text) {
        transcript.add(new ChatEntry(Speaker.VESSEL, text));
    }

    public void appendPlayerMessage(String text) {
        transcript.add(new ChatEntry(Speaker.PLAYER, text));
    }

    public void appendAssistantMessage(String text) {
        transcript.add(new ChatEntry(Speaker.ASSISTANT, text));
    }

    public void appendSystemMessage(String text) {
        transcript.add(new ChatEntry(Speaker.SYSTEM, text));
    }

    public boolean isClosed() {
        return closed;
    }

    /** {@code null} until {@link #close} is called. */
    public Deal.Outcome outcome() {
        return outcome;
    }

    public void close(Deal.Outcome outcome) {
        this.closed = true;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }
}
