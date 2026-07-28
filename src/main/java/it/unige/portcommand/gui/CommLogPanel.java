package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
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
 *
 * <p>Filters: performative, sender, receiver, and a lightweight sim-time range — all combine with
 * AND semantics in {@link CommLogModel}. A "&#9660; resume" button appears when the user has
 * scrolled up (so new entries are no longer auto-followed) and jumps back to the tail on click.
 */
public final class CommLogPanel extends JPanel {

    private static final String ALL = "All";
    /** The DF/local name the WeatherAgent registers under — the "Hide weather" toggle mutes it. */
    private static final String WEATHER_SENDER = "weather_agent";

    private static final Map<String, Integer> PERFORMATIVE_OPTIONS = buildPerformativeOptions();

    private final CommLogModel model = new CommLogModel();
    private final Subscription<CommLogEvent> subscription;
    private final Subscription<it.unige.portcommand.persistence.events.GameLoadedEvent> loadedSubscription;
    private final JTextPane textPane = new JTextPane();
    private final JScrollPane scrollPane;
    private final JComboBox<String> senderFilterCombo = new JComboBox<>(new String[] {ALL});
    private final JComboBox<String> receiverFilterCombo = new JComboBox<>(new String[] {ALL});
    private final JButton resumeButton = new JButton("▼ resume");

    /**
     * When non-null, overrides the live scrollbar "at bottom" arithmetic in {@link #isAtBottom()}.
     * Only ever set by the test seam: headless tests never lay the scrollpane out, so its scrollbar
     * reads 0/0/0 and can't express a real "user scrolled up" position. {@code null} in production.
     */
    private Boolean atBottomOverride;

    /** Whether the most recent {@link #render()} pinned the view to the bottom. Test-observable. */
    private boolean lastRenderAutoScrolled;

    public CommLogPanel(EventBus eventBus) {
        super(new BorderLayout());
        textPane.setEditable(false);
        scrollPane = new JScrollPane(textPane);
        add(scrollPane, BorderLayout.CENTER);

        JPanel filters = new JPanel();
        filters.setLayout(new BoxLayout(filters, BoxLayout.Y_AXIS));
        filters.add(buildFilterBar());
        filters.add(buildTimeRangeBar());
        add(filters, BorderLayout.NORTH);

        resumeButton.setVisible(false);
        resumeButton.addActionListener(e -> resumeToBottom());
        add(resumeButton, BorderLayout.SOUTH);

        // React to the user's OWN scrolling (drag / wheel), not just to a fresh render(): show the
        // resume button the moment they leave the bottom, hide it the moment they return.
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> updateResumeButton());

        this.subscription = eventBus.subscribe(CommLogEvent.class, this::onCommLogEvent, DeliveryMode.EDT);
        // Checkpoint-#6 F4 (fixed 2026-07-18): an in-place Game → Load replaced the whole
        // timeline but this log kept listing the old world's traffic — clean slate on the
        // loaded-marker event, the same rule ChatPanel applies to its tabs.
        this.loadedSubscription = eventBus.subscribe(
                it.unige.portcommand.persistence.events.GameLoadedEvent.class,
                e -> { model.clear(); render(); }, DeliveryMode.EDT);
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
        receiverFilterCombo.addActionListener(e -> {
            String selected = (String) receiverFilterCombo.getSelectedItem();
            model.setReceiverFilter(ALL.equals(selected) ? null : selected);
            render();
        });

        JCheckBox hideWeather = new JCheckBox("Hide weather");
        hideWeather.setToolTipText("Mute the weather agent's alerts, which otherwise dominate a busy log");
        hideWeather.addActionListener(e -> {
            model.setSenderMuted(WEATHER_SENDER, hideWeather.isSelected());
            render();
        });

        bar.add(new JLabel("Performative:"));
        bar.add(performativeFilterCombo);
        bar.add(new JLabel("Sender:"));
        bar.add(senderFilterCombo);
        bar.add(new JLabel("Receiver:"));
        bar.add(receiverFilterCombo);
        bar.add(hideWeather);
        return bar;
    }

    /**
     * Lightweight secondary filter: two free-text fields accepting either {@code HH:MM:SS} or a
     * plain millis number, applied on Enter, plus a Clear button that resets both bounds to
     * unbounded. Malformed text is silently ignored (treated as "no bound on that side") — never
     * throws on the EDT.
     */
    private JPanel buildTimeRangeBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JTextField fromField = new JTextField(8);
        JTextField toField = new JTextField(8);
        ActionListener apply = e -> {
            model.setTimeRange(parseTimeMillis(fromField.getText()), parseTimeMillis(toField.getText()));
            render();
        };
        fromField.addActionListener(apply);
        toField.addActionListener(apply);
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> {
            fromField.setText("");
            toField.setText("");
            model.setTimeRange(null, null);
            render();
        });

        bar.add(new JLabel("From:"));
        bar.add(fromField);
        bar.add(new JLabel("To:"));
        bar.add(toField);
        bar.add(clear);
        return bar;
    }

    private void onCommLogEvent(CommLogEvent event) {
        model.add(event);
        refreshSenderFilterOptions();
        refreshReceiverFilterOptions();
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

    /** Receiver-filter twin of {@link #refreshSenderFilterOptions()}; same add-only rationale, over
     * {@link CommLogModel#knownReceivers()}. */
    private void refreshReceiverFilterOptions() {
        for (String receiver : model.knownReceivers()) {
            if (!containsItem(receiverFilterCombo, receiver)) {
                receiverFilterCombo.addItem(receiver);
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
        boolean wasAtBottom = isAtBottom();

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
        lastRenderAutoScrolled = wasAtBottom;
        updateResumeButton();
    }

    /** True when the vertical scrollbar is at (or within a few px of) the bottom. Reused by both
     * {@link #render()}'s pin-if-was-at-bottom decision and the resume button's visibility, so the
     * two always agree on what "at bottom" means. */
    private boolean isAtBottom() {
        if (atBottomOverride != null) {
            return atBottomOverride;
        }
        JScrollBar vBar = scrollPane.getVerticalScrollBar();
        return vBar.getValue() + vBar.getVisibleAmount() >= vBar.getMaximum() - 4;
    }

    /** The resume button is exactly "you are NOT at the bottom": shown while scrolled up, hidden
     * once back at the tail. Complementary to {@link #render()}'s auto-pin (render keeps you pinned
     * when already at the bottom; this button is how you get back after scrolling away). */
    private void updateResumeButton() {
        resumeButton.setVisible(!isAtBottom());
    }

    private void resumeToBottom() {
        textPane.setCaretPosition(textPane.getDocument().getLength());
        resumeButton.setVisible(false);
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

    /**
     * Parses a time-range field: {@code HH:MM:SS} (MM/SS in 0..59, HH &gt;= 0, so multi-day sims can
     * still address hours &gt;= 24 as raw millis) or a plain millis number. Returns {@code null} for
     * blank OR malformed input — the caller treats {@code null} as "no bound on that side", so a
     * typo silently drops the bound rather than throwing.
     */
    private static Long parseTimeMillis(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            if (trimmed.contains(":")) {
                String[] parts = trimmed.split(":");
                if (parts.length != 3) {
                    return null;
                }
                long hours = Long.parseLong(parts[0].trim());
                long minutes = Long.parseLong(parts[1].trim());
                long seconds = Long.parseLong(parts[2].trim());
                if (hours < 0 || minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59) {
                    return null;
                }
                return ((hours * 60 + minutes) * 60 + seconds) * 1000L;
            }
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
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
        loadedSubscription.cancel();
    }

    /** Test-only: the model backing this panel's rendering. */
    CommLogModel model() {
        return model;
    }

    /**
     * Test-only: forces the {@link #isAtBottom()} decision (headless tests never lay the scrollpane
     * out, so its scrollbar can't report a real scrolled-up position). {@code null} restores the
     * live scrollbar arithmetic. Also refreshes the resume button so a simulated scroll is reflected
     * immediately, exactly as the real {@code AdjustmentListener} would.
     */
    void setAtBottomOverrideForTest(Boolean atBottom) {
        this.atBottomOverride = atBottom;
        updateResumeButton();
    }

    /** Test-only: the resume button, for visibility assertions and programmatic {@code doClick}. */
    JButton resumeButtonForTest() {
        return resumeButton;
    }

    /** Test-only: whether the most recent {@link #render()} pinned to the bottom. {@code false}
     * proves auto-scroll was suppressed because the user had scrolled up. */
    boolean lastRenderAutoScrolledForTest() {
        return lastRenderAutoScrolled;
    }
}
