package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import it.unige.portcommand.commlog.PerformativeColours;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.model.CommLogModel;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;
import jade.lang.acl.ACLMessage;

/**
 * Real comm log: renders the {@link CommLogEvent} stream as
 * {@code [HH:MM:SS] sender -> receivers: PERFORMATIVE "paraphrase"} lines, coloured via
 * {@link PerformativeColours}. {@link CommLogEvent} already carries the finished
 * {@code paraphrase()} text (produced by {@code commlog.Paraphraser} on the HarbourMaster's
 * send/receive path) — this panel never calls {@code Paraphraser} itself (the event doesn't even
 * carry the {@code ACLMessage} that method needs). Filter/bound logic lives in
 * {@link CommLogModel} (headless-testable); this class is the Swing rendering + the
 * autoscroll-unless-the-user-scrolled-up behaviour, which is inherently a UI concern.
 */
public final class CommLogPanel extends JPanel {

    private static final String ALL = "All";

    private static final Map<String, Integer> PERFORMATIVE_OPTIONS = buildPerformativeOptions();

    private final CommLogModel model = new CommLogModel();
    private final Subscription<CommLogEvent> subscription;
    private final JTextPane textPane = new JTextPane();
    private final JScrollPane scrollPane;
    private final JComboBox<String> senderFilterCombo = new JComboBox<>(new String[] {ALL});

    public CommLogPanel(EventBus eventBus) {
        super(new BorderLayout());
        textPane.setEditable(false);
        scrollPane = new JScrollPane(textPane);
        add(scrollPane, BorderLayout.CENTER);
        add(buildFilterBar(), BorderLayout.NORTH);

        this.subscription = eventBus.subscribe(CommLogEvent.class, this::onCommLogEvent, DeliveryMode.EDT);
    }

    private JPanel buildFilterBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JComboBox<String> performativeFilterCombo = new JComboBox<>(performativeOptions());
        performativeFilterCombo.addActionListener(e -> {
            String selected = (String) performativeFilterCombo.getSelectedItem();
            model.setPerformativeFilter(PERFORMATIVE_OPTIONS.get(selected));
            render();
        });
        senderFilterCombo.addActionListener(e -> {
            String selected = (String) senderFilterCombo.getSelectedItem();
            model.setSenderFilter(ALL.equals(selected) ? null : selected);
            render();
        });

        bar.add(new JLabel("Performative:"));
        bar.add(performativeFilterCombo);
        bar.add(new JLabel("Sender:"));
        bar.add(senderFilterCombo);
        return bar;
    }

    private void onCommLogEvent(CommLogEvent event) {
        model.add(event);
        refreshSenderFilterOptions();
        render();
    }

    /**
     * {@code addItem} on a non-empty {@link JComboBox} does not change the current selection or
     * fire an action event, so this never needs to detach the sender-filter listener. Only ADDS —
     * never rebuilds — because {@link CommLogModel#knownSenders()} only ever grows.
     */
    private void refreshSenderFilterOptions() {
        for (String sender : model.knownSenders()) {
            if (!containsItem(senderFilterCombo, sender)) {
                senderFilterCombo.addItem(sender);
            }
        }
    }

    private static boolean containsItem(JComboBox<String> combo, String value) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (value.equals(combo.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Rebuilds the visible text from {@code model.filteredEntries()}; only forces the scroll
     * position back to the bottom if it was already there before this render (never yanks a user
     * who scrolled up to read history back down). */
    private void render() {
        JScrollBar vBar = scrollPane.getVerticalScrollBar();
        boolean wasAtBottom = vBar.getValue() + vBar.getVisibleAmount() >= vBar.getMaximum() - 4;

        StyledDocument doc = textPane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            for (CommLogEvent event : model.filteredEntries()) {
                appendLine(doc, event);
            }
        } catch (BadLocationException e) {
            // remove/insertString with offsets derived from getLength() never throws in practice;
            // degrade to whatever partial content rendered rather than crash the EDT.
        }

        if (wasAtBottom) {
            textPane.setCaretPosition(textPane.getDocument().getLength());
        }
    }

    private void appendLine(StyledDocument doc, CommLogEvent event) throws BadLocationException {
        Style style = textPane.addStyle(null, null);
        StyleConstants.setForeground(style, PerformativeColours.colourFor(event.performative()));
        doc.insertString(doc.getLength(), formatLine(event) + "\n", style);
    }

    private static String formatLine(CommLogEvent event) {
        return String.format("[%s] %s -> %s: %s \"%s\"",
                formatTimestamp(event.simTimeMillis()), event.sender(), String.join(",", event.receivers()),
                ACLMessage.getPerformative(event.performative()), event.paraphrase());
    }

    /** Formatted from the event's own {@code simTimeMillis} — no {@code SimClock} reference
     * needed, using the same hour/minute/second modular arithmetic {@code SimClock} itself uses. */
    private static String formatTimestamp(long simTimeMillis) {
        long hour = (simTimeMillis / 3_600_000L) % 24;
        long minute = (simTimeMillis / 60_000L) % 60;
        long second = (simTimeMillis / 1_000L) % 60;
        return String.format("%02d:%02d:%02d", hour, minute, second);
    }

    private static String[] performativeOptions() {
        String[] options = new String[PERFORMATIVE_OPTIONS.size() + 1];
        options[0] = ALL;
        int i = 1;
        for (String name : PERFORMATIVE_OPTIONS.keySet()) {
            options[i++] = name;
        }
        return options;
    }

    private static Map<String, Integer> buildPerformativeOptions() {
        int[] canonicalTen = {
                ACLMessage.REQUEST, ACLMessage.PROPOSE, ACLMessage.ACCEPT_PROPOSAL, ACLMessage.REJECT_PROPOSAL,
                ACLMessage.CFP, ACLMessage.CONFIRM, ACLMessage.INFORM, ACLMessage.REFUSE, ACLMessage.CANCEL,
                ACLMessage.DISCONFIRM,
        };
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int performative : canonicalTen) {
            map.put(ACLMessage.getPerformative(performative), performative);
        }
        return map;
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        subscription.cancel();
    }

    /** Test-only: the model backing this panel's rendering. */
    CommLogModel model() {
        return model;
    }
}
