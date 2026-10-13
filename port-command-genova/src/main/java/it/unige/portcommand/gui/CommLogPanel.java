package it.unige.portcommand.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import it.unige.portcommand.commlog.PerformativeColours;
import it.unige.portcommand.core.RuleCatalogue;
import it.unige.portcommand.gui.events.CommLogEvent;
import it.unige.portcommand.gui.model.CommLogLineFormat;
import it.unige.portcommand.gui.model.CommLogModel;
import it.unige.portcommand.gui.model.CommLogPreset;
import it.unige.portcommand.gui.model.RuleRefs;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * The comm log, right column: the {@link CommLogEvent} stream as one row per ACL message, coloured
 * via {@link PerformativeColours}. A row is either the plain line
 * {@code [HH:MM:SS] sender -> receivers: PERFORMATIVE "paraphrase"} or, with Raw ticked, the
 * message's real FIPA fields — both texts are {@link CommLogLineFormat}'s. The paraphrase was
 * produced by {@code commlog.Paraphraser} on the HarbourMaster's send/receive path; this panel
 * never calls it. What is stored, bounded, archived and selected is {@link CommLogModel}'s
 * (headless-testable); this class is the Swing side: the controls, the rendering, the clickable
 * rule ids and the autoscroll-unless-the-user-scrolled-up behaviour, which is inherently a UI
 * concern.
 *
 * <p><b>S15 (2026-10-12): four views instead of five filters.</b> The performative / sender /
 * receiver combos, "Hide weather" and the From/To fields are gone. They were the widgets that
 * swallowed the player's typing whenever the focus fell out of the chat input, and nobody could
 * say in one sentence what a combination of them showed. One row of toggles replaces them — All,
 * This vessel, Rules, Weather ({@link CommLogPreset}) — plus the Raw switch.
 *
 * <p><b>Nothing in this column takes the keyboard focus</b> — not the toggles, not the text, not
 * the scrollbars. A click on a rule id therefore never pulls the typing focus out of the chat, and
 * AWT's "the focused component went away, pick another" fallback has nowhere to land here. The
 * price is no keyboard selection or copy in the log; the wheel and the scrollbar still work.
 *
 * <p><b>Rule ids are links.</b> A catalogued id inside a bracket group ({@code [R7]},
 * {@code [R25,R26,R27,R30]} — {@link RuleRefs}) is underlined; pressing the mouse on it writes
 * the rule's one-line text ({@link RuleCatalogue}) into the rule line under the log. That line is
 * a FIXED two rows and keeps its text until another id is clicked: a tooltip needs a hover and
 * vanishes on the next mouse move, a popup takes the focus and interrupts a real-time game, and
 * neither can be narrated in a demo. Fixed, because a line that grew with its text would shove
 * the log up and down under the reader.
 *
 * <p><b>Scrolling.</b> New rows are followed only while the view is at the tail; once the user
 * scrolls up the view stays put and a "&#9660; resume" button offers the way back. A change of
 * VIEW (another preset, Raw, another vessel under This vessel) always goes to the tail instead:
 * a row is one line in one view and four in another, so the old offset would point at nothing in
 * particular.
 *
 * <p>"Following the tail" is remembered ({@link #followTail}), not worked out from the scrollbar
 * each time. Only the user's own scrolling ends it. A layout change does not: dragging a column
 * divider re-wraps the text, a notification chip makes the column a few pixels shorter, and in
 * both cases the scrollbar ends up short of its maximum although nobody scrolled — the view is
 * then taken back to the tail. And "stays put" means the ROW being read, not the pixel offset:
 * once the log holds its 500 rows every new message drops the oldest one, so the same offset
 * would show a later row each time ({@link RowAnchor}).
 */
public final class CommLogPanel extends JPanel {

    // S15: component names, for the tests and for anything that has to find a control in the
    // realised window. The panel's OWN name is the window shell's to set.
    static final String NAME_PRESET_ALL = "commlog-preset-all";
    static final String NAME_PRESET_THIS_VESSEL = "commlog-preset-this-vessel";
    static final String NAME_PRESET_RULES = "commlog-preset-rules";
    static final String NAME_PRESET_WEATHER = "commlog-preset-weather";
    static final String NAME_RAW = "commlog-raw";
    static final String NAME_TEXT = "commlog-text";
    static final String NAME_RULE_LINE = "commlog-rule-line";
    static final String NAME_RESUME = "commlog-resume";

    static final String TIP_ALL = "Every message";
    /** While no chat tab is selected; with one, the tooltip names its vessel instead. */
    static final String TIP_THIS_VESSEL = "Messages about the vessel whose chat tab is selected";
    static final String TIP_RULES = "Messages that cite a Prolog rule, e.g. [R7]";
    static final String TIP_WEATHER =
            "Weather alerts, routine checks, and the holds, resumes and tug cancels they cause";
    static final String TIP_RAW = "Show each message's real FIPA fields instead of the plain-English line";

    /** What the rule line says until the first rule id is clicked. */
    static final String RULE_LINE_PROMPT = "Click a rule id such as [R7] in the log to read the rule.";

    /** Two rows hold the longest catalogue text ({@link RuleCatalogue#MAX_TEXT_CHARS}) at 380 px. */
    private static final int RULE_LINE_ROWS = 2;

    private static final Color HINT_COLOUR = new Color(0x80, 0x80, 0x80);
    private static final Color RULE_LINE_SEPARATOR = new Color(0xC8, 0xC8, 0xC8);

    /** The empty-state line: grey, and never underlined or clickable. */
    private static final AttributeSet HINT_STYLE = foreground(HINT_COLOUR);
    /** Added ON TOP of a row's colour for the characters of a rule id; carries nothing else. */
    private static final AttributeSet RULE_ID_UNDERLINE = underline();

    private final CommLogModel model;
    /** S15: the texts behind the clickable ids — fetched here so a broken catalogue fails at build. */
    private final RuleCatalogue catalogue = RuleCatalogue.get();
    private final Predicate<String> knownRuleId = catalogue::contains;
    private final Subscription<CommLogEvent> subscription;
    private final Subscription<GameLoadedEvent> loadedSubscription;
    private final JTextPane textPane = new JTextPane();
    private final JScrollPane scrollPane;
    private final Map<CommLogPreset, JToggleButton> presetToggles = new EnumMap<>(CommLogPreset.class);
    private final JCheckBox rawToggle = new JCheckBox("Raw");
    private final JTextArea ruleLine = new RuleLine(RULE_LINE_PROMPT);
    private final JButton resumeButton = new JButton("▼ resume");

    /**
     * One attribute set per performative colour, made on first use (S15). A render used to call
     * {@code addStyle} once per row — up to 500 throwaway styles per arriving message; the ten
     * performatives and the grey fallback need eleven.
     */
    private final Map<Integer, AttributeSet> rowStyles = new HashMap<>();

    /**
     * S15: where the rule ids of the CURRENT rendering sit, in document offsets ({@code [start,
     * end)} covers the id only). Rebuilt by every render, because every render rebuilds the text.
     */
    private List<RuleRefs.Ref> ruleSpans = List.of();

    /**
     * S15: the rows of the CURRENT rendering, in display order, and the document offset each one
     * starts at ({@code rowStarts[i]} belongs to {@code renderedRows.get(i)}). Rebuilt by every
     * render. They are what lets a scrolled-up view be put back on the row it was showing.
     */
    private List<CommLogEvent> renderedRows = List.of();
    private int[] rowStarts = new int[0];

    /**
     * S15: whether the view follows the tail. {@code true} until the USER scrolls away from the
     * bottom (wheel, thumb, track, arrows), and {@code true} again when they scroll back, press
     * resume or change the view. State, on purpose: read off the scrollbar instead, every layout
     * change that leaves the bar short of its maximum would count as "the user scrolled up" and
     * the log would quietly stop following.
     */
    private boolean followTail = true;

    /**
     * The scrollbar's extent and maximum as the adjustment listener last saw them. If either
     * differs, the event is the LAYOUT changing (a longer document, a re-wrap, a resized column);
     * if both are the same, only the value moved, and that is the user scrolling.
     */
    private int lastExtent = -1;
    private int lastMaximum = -1;
    /** The value it last saw: an event that moved nothing (a thumb press) decides nothing. */
    private int lastValue = -1;

    /** Whether the most recent {@link #render} pinned the view to the bottom. Test-observable. */
    private boolean lastRenderAutoScrolled;

    /**
     * S15: how many view jumps ({@link #scrollToTail()}, {@link #restoreLater}) are requested but
     * not yet carried out — a jump runs one EDT turn later, once the scroll pane knows the new
     * document's height. While one is pending, what the scrollbar reports about the LAYOUT is
     * not reacted to: the jump on its way settles the view. The user's own wheel or thumb is
     * still obeyed, and overtakes the jump (see {@link #onScrollBarAdjusted()}).
     */
    private int viewJumpsPending;

    /** Counts the jumps requested so far; a jump that is no longer the newest one does nothing. */
    private int jumpGeneration;

    /**
     * The row a scrolled-up view is being put back on while that jump is still pending. A second
     * message of the same burst re-uses it: the viewport has not moved yet, so reading the top row
     * off it again would name the wrong one.
     */
    private RowAnchor pendingAnchor;

    private boolean handCursorShown;

    public CommLogPanel(EventBus eventBus) {
        this(eventBus, new CommLogModel());
    }

    /**
     * Test seam: a panel over a given model — one whose evicted rows go to a temp directory, for
     * the tests that fill the log to its cap (the production model archives under {@code logs/}).
     */
    CommLogPanel(EventBus eventBus, CommLogModel model) {
        super(new BorderLayout());
        this.model = Objects.requireNonNull(model, "model");

        textPane.setName(NAME_TEXT);
        textPane.setEditable(false);
        // S15: the caret must NOT follow the document. With the default policy, a full rebuild
        // (remove everything, insert every row) drags the caret to the end and the caret scrolls
        // the view after it — so in the live window the log could never stay scrolled up, whatever
        // render() decided. From here on the view moves only when scrollToTail() says so.
        // Nor may the caret scroll it on a mouse press: a press moves the caret to the clicked
        // row and the stock caret then scrolls that row fully into view — a click on a rule id in
        // a half-visible top row would shift the log and read as "the user scrolled".
        textPane.setCaret(new StillCaret());
        RuleMouse ruleMouse = new RuleMouse();
        textPane.addMouseListener(ruleMouse);
        textPane.addMouseMotionListener(ruleMouse);
        scrollPane = new JScrollPane(textPane);
        // The text wraps to the column; a horizontal bar would only ever appear for one stray
        // long token and cost a row of the log's height while it did.
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);

        add(buildControlRow(), BorderLayout.NORTH);

        ruleLine.setName(NAME_RULE_LINE);
        ruleLine.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, RULE_LINE_SEPARATOR),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        resumeButton.setName(NAME_RESUME);
        resumeButton.setVisible(false);
        resumeButton.addActionListener(e -> resumeToBottom());
        JPanel south = new JPanel(new BorderLayout());
        south.add(ruleLine, BorderLayout.CENTER);
        south.add(resumeButton, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> onScrollBarAdjusted());

        // S15: after everything is in the tree, so the scroll pane's own children (viewport,
        // scrollbars, arrow buttons) are covered as well as the controls built above.
        refuseFocus(this);

        // The pane is never blank: before the first message it already says "No messages yet."
        // Text only — no scroll decision, and the resume button stays as it is: nothing is laid
        // out yet, so "am I at the bottom?" has no answer to give.
        rebuildDocument();

        this.subscription = eventBus.subscribe(CommLogEvent.class, this::onCommLogEvent, DeliveryMode.EDT);
        // Checkpoint-#6 F4 (fixed 2026-07-18): an in-place Game → Load replaced the whole
        // timeline but this log kept listing the old world's traffic — clean slate on the
        // loaded-marker event, the same rule ChatPanel applies to its tabs.
        this.loadedSubscription = eventBus.subscribe(GameLoadedEvent.class, this::onGameLoaded, DeliveryMode.EDT);
    }

    /**
     * S15: the one control row — four view toggles, then Raw at the right edge. Toggles in a
     * {@link ButtonGroup}, not a combo: one click, the choice is always visible, and a toggle can
     * be made unfocusable without losing anything a mouse user needs.
     */
    private JPanel buildControlRow() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        ButtonGroup presets = new ButtonGroup();
        row.add(presetToggle(presets, CommLogPreset.ALL, "All", NAME_PRESET_ALL, TIP_ALL));
        row.add(presetToggle(presets, CommLogPreset.THIS_VESSEL, "This vessel", NAME_PRESET_THIS_VESSEL,
                TIP_THIS_VESSEL));
        row.add(presetToggle(presets, CommLogPreset.RULES, "Rules", NAME_PRESET_RULES, TIP_RULES));
        row.add(presetToggle(presets, CommLogPreset.WEATHER, "Weather", NAME_PRESET_WEATHER, TIP_WEATHER));
        // setSelected fires no action event, so this only makes the row show the model's default.
        presetToggles.get(model.preset()).setSelected(true);

        rawToggle.setName(NAME_RAW);
        rawToggle.setToolTipText(TIP_RAW);
        rawToggle.addActionListener(e -> render(true));
        row.add(Box.createHorizontalGlue());
        row.add(rawToggle);
        return row;
    }

    private JToggleButton presetToggle(ButtonGroup group, CommLogPreset preset, String label, String name,
                                       String tooltip) {
        JToggleButton toggle = new JToggleButton(label);
        toggle.setName(name);
        toggle.setToolTipText(tooltip);
        // Tighter than the look-and-feel default: the four toggles and Raw have to fit one row of
        // a 380 px column.
        toggle.setMargin(new Insets(2, 8, 2, 8));
        toggle.addActionListener(e -> choosePreset(preset));
        group.add(toggle);
        presetToggles.put(preset, toggle);
        return toggle;
    }

    /**
     * A click on the view that is already showing changes nothing and so must not move the
     * reader; only a real change re-renders (and goes to the tail, like every view change).
     */
    private void choosePreset(CommLogPreset preset) {
        if (model.preset() == preset) {
            return;
        }
        model.setPreset(preset);
        render(true);
    }

    /** S15: see the class comment — nothing in this column may take the keyboard focus. */
    private static void refuseFocus(Component component) {
        component.setFocusable(false);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                refuseFocus(child);
            }
        }
    }

    private void onCommLogEvent(CommLogEvent event) {
        model.add(event);
        render(false);
    }

    /**
     * S15: the model's {@code clear()} keeps the focus vessel by contract (it is a view setting),
     * so the panel drops it here — the vessel belonged to the world that was just replaced. The
     * chat column reports "no tab" after a load as well; this is the backstop for the case where
     * that report is late or missing.
     */
    private void onGameLoaded(GameLoadedEvent event) {
        model.clear();
        model.setFocusVessel(null);
        refreshThisVesselTooltip();
        render(false);
    }

    /**
     * S15: tells the log which vessel "This vessel" means — the raw id of the selected chat tab's
     * vessel ({@code WALKIN-3}), or {@code null} when no tab is selected. EDT only. It may be
     * called under any view and as often as the chat likes: the log is re-rendered only when the
     * vessel really changed AND the This-vessel view is the one showing.
     */
    public void setFocusVessel(String vesselIdOrNull) {
        String before = model.focusVesselId();
        model.setFocusVessel(vesselIdOrNull);
        refreshThisVesselTooltip();
        if (model.preset() == CommLogPreset.THIS_VESSEL && !Objects.equals(before, model.focusVesselId())) {
            render(true);
        }
    }

    private void refreshThisVesselTooltip() {
        String vesselId = model.focusVesselId();
        presetToggles.get(CommLogPreset.THIS_VESSEL).setToolTipText(vesselId == null
                ? TIP_THIS_VESSEL
                : "Messages about " + vesselId + " — the selected chat tab");
    }

    /**
     * Rebuilds the visible text, then decides where the view goes.
     *
     * @param viewChanged {@code true} when WHAT is shown changed (preset, Raw, the focus vessel
     *                    under This vessel): always go to the tail. {@code false} when rows only
     *                    arrived or were cleared: go to the tail only if the view was already
     *                    there — never yank a user who scrolled up to read history back down.
     */
    private void render(boolean viewChanged) {
        boolean pin = viewChanged || followTail;
        // Scrolled up: note which row is at the top BEFORE the text is replaced (or keep the one
        // a still-pending jump is already heading for).
        RowAnchor anchor = pin ? null : (pendingAnchor != null ? pendingAnchor : topRow());

        rebuildDocument();

        if (pin) {
            followTail = true;
            scrollToTail();
            showResumeButton(false);
        } else {
            if (anchor != null) {
                restoreLater(anchor);
            }
            showResumeButton(true);
        }
        lastRenderAutoScrolled = pin;
    }

    /**
     * The scrollbar reported a change. Two very different things arrive here, told apart by
     * whether the bar's extent or maximum changed (see {@link #lastExtent}):
     *
     * <ul>
     *   <li><b>only the value moved</b> — the user's wheel, thumb, track or arrow. This, and
     *       nothing else, decides whether the view still follows the tail; the resume button
     *       shows exactly while it does not.</li>
     *   <li><b>the layout changed</b> — never a reason to stop following. If the view was
     *       following, it is taken back to the tail (the bar keeps its value when the document
     *       grows or the column shrinks, which leaves it short of the new maximum). If it was
     *       not, it is left alone — unless the change itself brought the tail into view (the
     *       window grew, the log was emptied), where there is nothing left to resume.</li>
     * </ul>
     *
     * While a jump is pending, layout reports are ignored — the jump settles the view when it
     * runs. But the user can scroll in exactly that gap (a wheel notch that was already in the
     * event queue when a message was rendered is dispatched before the jump), and the jump
     * would then undo it. So a value the bar was given inside the dispatch of a MOUSE event —
     * wheel, thumb, track, arrow — is obeyed there too, and the pending jump is called off.
     * (A layout pass can also move the value alone, by clamping it under a shorter document;
     * that runs under an invocation event, not a mouse event, and stays ignored.)
     */
    private void onScrollBarAdjusted() {
        JScrollBar vBar = scrollPane.getVerticalScrollBar();
        boolean layoutChanged = vBar.getVisibleAmount() != lastExtent || vBar.getMaximum() != lastMaximum;
        boolean valueMoved = vBar.getValue() != lastValue;
        lastExtent = vBar.getVisibleAmount();
        lastMaximum = vBar.getMaximum();
        lastValue = vBar.getValue();
        if (viewJumpsPending > 0) {
            if (!layoutChanged && valueMoved && EventQueue.getCurrentEvent() instanceof MouseEvent) {
                followTail = scrollBarAtTail();
                if (followTail) {
                    scrollToTail(); // the maximum may still be the old one: go to the real tail
                } else {
                    jumpGeneration++; // the jump on its way is no longer the newest: it does nothing
                    pendingAnchor = null;
                }
                showResumeButton(!followTail);
            }
            return;
        }
        if (!layoutChanged) {
            followTail = scrollBarAtTail();
            showResumeButton(!followTail);
        } else if (followTail) {
            scrollToTail();
        } else if (scrollBarAtTail()) {
            followTail = true;
            showResumeButton(false);
        }
    }

    /**
     * Still a full rebuild: remove everything, insert every row of {@code model.filteredEntries()}
     * in its colour, underline the rule ids. S15: an empty list is not a blank pane — it shows
     * the model's one-line reason instead ("No weather messages yet.").
     */
    private void rebuildDocument() {
        StyledDocument doc = textPane.getStyledDocument();
        List<RuleRefs.Ref> spans = new ArrayList<>();
        List<CommLogEvent> rows = model.filteredEntries();
        int[] starts = new int[rows.size()];
        int rendered = 0;
        try {
            doc.remove(0, doc.getLength());
            if (rows.isEmpty()) {
                // A hint is never linkified, whatever it says: nothing is searched in it.
                doc.insertString(0, model.emptyStateHint(), HINT_STYLE);
            } else {
                boolean raw = rawToggle.isSelected();
                for (CommLogEvent event : rows) {
                    int rowStart = doc.getLength();
                    appendRow(doc, raw ? CommLogLineFormat.raw(event) : CommLogLineFormat.plain(event),
                            event.performative(), spans);
                    starts[rendered++] = rowStart;
                }
            }
        } catch (BadLocationException e) {
            // remove/insertString with offsets derived from getLength() never throws in practice;
            // degrade to whatever partial content rendered rather than crash the EDT.
        }
        // What was recorded so far matches the text that did get in, also after a partial render.
        ruleSpans = List.copyOf(spans);
        renderedRows = List.copyOf(rows.subList(0, rendered));
        rowStarts = Arrays.copyOf(starts, rendered);
    }

    /**
     * Inserts one row and records its rule ids. The ids are looked for in the row's text exactly
     * as it is inserted, so an offset {@link RuleRefs} reports is, plus the row's start, an offset
     * in the document — in the plain line and in the four-line Raw block alike (where an id sits
     * inside the content).
     */
    private void appendRow(StyledDocument doc, String rowText, int performative, List<RuleRefs.Ref> spans)
            throws BadLocationException {
        int rowStart = doc.getLength();
        doc.insertString(rowStart, rowText + "\n", rowStyle(performative));
        for (RuleRefs.Ref ref : RuleRefs.find(rowText, knownRuleId)) {
            // replace=false MERGES: the id keeps its row's colour and gains the underline.
            doc.setCharacterAttributes(rowStart + ref.start(), ref.end() - ref.start(), RULE_ID_UNDERLINE, false);
            spans.add(new RuleRefs.Ref(rowStart + ref.start(), rowStart + ref.end(), ref.ruleId()));
        }
    }

    private AttributeSet rowStyle(int performative) {
        return rowStyles.computeIfAbsent(performative, p -> foreground(PerformativeColours.colourFor(p)));
    }

    private static AttributeSet foreground(Color colour) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, colour);
        return attributes;
    }

    private static AttributeSet underline() {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setUnderline(attributes, true);
        return attributes;
    }

    /** True when the vertical scrollbar is at (or within a few px of) the bottom. */
    private boolean scrollBarAtTail() {
        JScrollBar vBar = scrollPane.getVerticalScrollBar();
        return vBar.getValue() + vBar.getVisibleAmount() >= vBar.getMaximum() - 4;
    }

    /**
     * S15: the button now shares the bottom strip with the rule line, and hiding a component does
     * not re-lay-out its parent by itself (only showing one does) — without the explicit
     * {@code revalidate()} a hidden button would leave an empty band under the rule line until
     * the next window resize.
     *
     * <p>Asked twice, the second time after the current event. The scrollbar listener can call
     * this in the MIDDLE of a validation pass that covers this panel (the window was made
     * taller, the notification strip changed height): that pass marks the panel valid again
     * when it finishes, and a revalidate made inside it is lost — the band would be back.
     */
    private void showResumeButton(boolean show) {
        if (resumeButton.isVisible() == show) {
            return;
        }
        resumeButton.setVisible(show);
        revalidate();
        SwingUtilities.invokeLater(this::revalidate);
    }

    private void resumeToBottom() {
        followTail = true;
        scrollToTail();
        showResumeButton(false);
    }

    /**
     * S15: the one way the view goes to the tail — used by a pinned render, by the resume button
     * and by a layout change under a view that follows the tail. The scrollbar is set directly,
     * one EDT turn later: by then the scroll pane has been re-laid-out for the new document and
     * its maximum is the real one. (The caret is no help here — it never scrolls this pane, see
     * {@link StillCaret}.)
     *
     * <p>Until that turn has run, {@link #viewJumpsPending} keeps the scrollbar listener quiet.
     */
    private void scrollToTail() {
        int generation = ++jumpGeneration;
        pendingAnchor = null; // a jump to a row that is still on its way is overtaken by this one
        viewJumpsPending++;
        SwingUtilities.invokeLater(() -> {
            boolean newest = generation == jumpGeneration;
            try {
                if (newest) {
                    JScrollBar vBar = scrollPane.getVerticalScrollBar();
                    vBar.setValue(vBar.getMaximum()); // the model clamps this to maximum - extent
                }
            } finally {
                viewJumpsPending--;
            }
            if (newest) {
                // At the tail by construction — said directly, not asked of the scrollbar (the
                // listener was quiet during the jump).
                showResumeButton(false);
            }
        });
    }

    /**
     * A row and how far above the top edge of the viewport it starts: where a scrolled-up view
     * is. Kept by the row itself (the very event object the model holds), because that is what
     * survives a rebuild — a document offset or a pixel offset means another row as soon as the
     * oldest one has been dropped.
     */
    private record RowAnchor(CommLogEvent row, double pixelsAboveViewTop) {
    }

    /**
     * The row at the top edge of the viewport, or {@code null} when that has no answer: nothing is
     * laid out yet, or the pane shows the empty-state hint.
     */
    private RowAnchor topRow() {
        if (renderedRows.isEmpty() || textPane.getHeight() <= 0) {
            return null;
        }
        int viewTop = scrollPane.getViewport().getViewPosition().y;
        int position = textPane.viewToModel2D(new Point(0, viewTop));
        if (position < 0) {
            return null;
        }
        int found = Arrays.binarySearch(rowStarts, position);
        int row = Math.max(0, found >= 0 ? found : -found - 2); // the last row starting at or before it
        try {
            Rectangle2D rowBox = textPane.modelToView2D(rowStarts[row]);
            return rowBox == null ? null : new RowAnchor(renderedRows.get(row), viewTop - rowBox.getY());
        } catch (BadLocationException e) {
            return null;
        }
    }

    /**
     * Puts a scrolled-up view back on {@code anchor} once the rebuilt document has been laid out
     * (one EDT turn later, like {@link #scrollToTail()}): the same row, the same distance above
     * the top edge. If the row itself has been dropped from the log meanwhile, the view goes to
     * the oldest row there is — the nearest thing left to what was being read.
     */
    private void restoreLater(RowAnchor anchor) {
        int generation = ++jumpGeneration;
        pendingAnchor = anchor;
        viewJumpsPending++;
        SwingUtilities.invokeLater(() -> {
            boolean newest = generation == jumpGeneration;
            try {
                if (newest) {
                    pendingAnchor = null;
                    scrollTo(anchor);
                }
            } finally {
                viewJumpsPending--;
            }
            if (newest) {
                // The listener was quiet during the jump, so what the bar now says is read here:
                // an anchor a few pixels above the tail can land on the tail itself.
                followTail = scrollBarAtTail();
                showResumeButton(!followTail);
            }
        });
    }

    private void scrollTo(RowAnchor anchor) {
        if (renderedRows.isEmpty()) {
            return;
        }
        int row = 0;
        double offset = 0;
        for (int i = 0; i < renderedRows.size(); i++) {
            if (renderedRows.get(i) == anchor.row()) { // identity: two equal rows are still two rows
                row = i;
                offset = anchor.pixelsAboveViewTop();
                break;
            }
        }
        try {
            Rectangle2D rowBox = textPane.modelToView2D(rowStarts[row]);
            if (rowBox != null) {
                // The model clamps this to [0, maximum - extent].
                scrollPane.getVerticalScrollBar().setValue((int) Math.round(rowBox.getY() + offset));
            }
        } catch (BadLocationException e) {
            // the document changed again under this jump; the next render asks again
        }
    }

    // ------------------------------------------------------------------------------------------
    // S15: clickable rule ids
    // ------------------------------------------------------------------------------------------

    /**
     * The rule id drawn under a point of the text pane, if any.
     *
     * <p>{@code viewToModel2D} answers with the NEAREST text position, not "the character under
     * the point", so on its own it would count a click on the right half of the bracket before an
     * id, or on the left half of the bracket after it, or below the last row, as a click on the
     * id. So the position (and the one before it — a click on the right half of a glyph is nearest
     * to the position after that glyph) only nominates a candidate, and {@link #isDrawnUnder}
     * confirms it against where the id is really painted.
     */
    private Optional<RuleRefs.Ref> spanAt(Point2D point) {
        if (ruleSpans.isEmpty()) {
            return Optional.empty();
        }
        int position = textPane.viewToModel2D(point);
        if (position < 0) {
            return Optional.empty(); // not laid out yet
        }
        Optional<RuleRefs.Ref> candidate = RuleRefs.at(ruleSpans, position);
        if (candidate.isEmpty()) {
            candidate = RuleRefs.at(ruleSpans, position - 1);
        }
        return candidate.filter(span -> isDrawnUnder(span, point));
    }

    /** Whether {@code point} lies inside the box the span's characters are painted in. */
    private boolean isDrawnUnder(RuleRefs.Ref span, Point2D point) {
        try {
            Rectangle2D first = textPane.modelToView2D(span.start());
            Rectangle2D last = textPane.modelToView2D(span.end());
            if (first == null || last == null) {
                return false;
            }
            boolean onFirstRow = point.getY() >= first.getMinY() && point.getY() < first.getMaxY();
            if (first.getMinY() == last.getMinY()) {
                return onFirstRow && point.getX() >= first.getMinX() && point.getX() < last.getMinX();
            }
            // The id itself was broken over two rows. Text wraps at spaces and an id has none, so
            // this takes a column narrower than "R25" — handled only so that it is not a dead id.
            boolean onLastRow = point.getY() >= last.getMinY() && point.getY() < last.getMaxY();
            return (onFirstRow && point.getX() >= first.getMinX())
                    || (onLastRow && point.getX() < last.getMinX());
        } catch (BadLocationException e) {
            return false; // the document changed under the pointer; the next event asks again
        }
    }

    /**
     * Writes the rule into the rule line: {@code R7 — <one-line text>}, with the predicate and its
     * kernel file as the tooltip ({@code compatible/2 · rules_compatibility.pl}). It stays until
     * another id is clicked — arriving messages, another view and a loaded game leave it alone.
     */
    private void showRule(String ruleId) {
        Optional<RuleCatalogue.Rule> found = catalogue.find(ruleId);
        if (found.isEmpty()) {
            return; // not reachable: a span is only recorded for an id the catalogue knows
        }
        RuleCatalogue.Rule rule = found.get();
        ruleLine.setText(rule.id() + " — " + rule.text());
        ruleLine.setToolTipText(rule.predicate() + " · " + rule.module() + ".pl");
    }

    private void showHandCursor(boolean overRuleId) {
        if (overRuleId == handCursorShown) {
            return;
        }
        handCursorShown = overRuleId;
        // null hands the cursor back to the parent's (the plain arrow): a read-only pane has no
        // text cursor of its own to restore.
        textPane.setCursor(overRuleId ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
    }

    /**
     * The text pane's mouse handling for rule ids. The rule shows on PRESS, not on a completed
     * click: AWT drops the click event when the pointer moves between press and release, and a
     * link that sometimes ignores a slightly unsteady hand is worse than one that answers early.
     */
    private final class RuleMouse extends MouseAdapter {

        @Override
        public void mousePressed(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                spanAt(e.getPoint()).ifPresent(span -> showRule(span.ruleId()));
            }
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            showHandCursor(spanAt(e.getPoint()).isPresent());
        }

        @Override
        public void mouseExited(MouseEvent e) {
            showHandCursor(false);
        }
    }

    /**
     * A caret that never moves the view. The stock one scrolls its position into view whenever it
     * moves — after a document change (switched off by its update policy) and after a mouse press
     * (which no policy switches off). In this pane only {@link #scrollToTail()} and
     * {@link #restoreLater} may scroll.
     */
    private static final class StillCaret extends DefaultCaret {
        private static final long serialVersionUID = 1L;

        StillCaret() {
            setUpdatePolicy(NEVER_UPDATE);
        }

        @Override
        protected void adjustVisibility(Rectangle newLocation) {
            // never
        }
    }

    /**
     * The rule line: a read-only, wrapping text area that is ALWAYS two rows high.
     *
     * <p>A wrapping text area reports its size from the width it currently has — before the first
     * layout that is the whole sentence on one line, and afterwards its minimum width is whatever
     * width it was last given, so inside a split pane the column could be widened but never
     * narrowed again. Both sizes are therefore stated here: no width of its own (the column gives
     * it one), and a height of exactly {@code RULE_LINE_ROWS} rows, so clicking a longer or a
     * shorter rule never moves the log above it.
     */
    private static final class RuleLine extends JTextArea {

        RuleLine(String text) {
            super(text, RULE_LINE_ROWS, 0);
            setLineWrap(true);
            setWrapStyleWord(true);
            setEditable(false);
            setOpaque(false);
            setFocusable(false);
            // setText must not ask an enclosing viewport to scroll to this component's caret.
            if (getCaret() instanceof DefaultCaret caret) {
                caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Insets insets = getInsets();
            return new Dimension(insets.left + insets.right,
                    RULE_LINE_ROWS * getRowHeight() + insets.top + insets.bottom);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }
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
     * Test-only: puts the panel in the state the user's own scrolling would — {@code false} is
     * "scrolled up", {@code true} "back at the tail" — for the tests that never lay the scroll
     * pane out and so have no scrollbar to move. The resume button follows at once, exactly as it
     * does after a real scroll.
     */
    void setFollowingTailForTest(boolean following) {
        this.followTail = following;
        showResumeButton(!following);
    }

    /** Test-only: whether the view currently follows the tail. */
    boolean followingTailForTest() {
        return followTail;
    }

    /** Test-only: the log's scroll pane, for the tests that lay it out and move its scrollbar. */
    JScrollPane scrollPaneForTest() {
        return scrollPane;
    }

    /**
     * Test-only: the row at the top edge of the viewport ({@code null} while nothing is laid out
     * or the pane shows the empty-state hint) — the very object the model holds.
     */
    CommLogEvent topRowForTest() {
        RowAnchor top = topRow();
        return top == null ? null : top.row();
    }

    /** Test-only: the resume button, for visibility assertions and programmatic {@code doClick}. */
    JButton resumeButtonForTest() {
        return resumeButton;
    }

    /** Test-only: whether the most recent {@link #render} pinned to the bottom. {@code false}
     * proves auto-scroll was suppressed because the user had scrolled up. */
    boolean lastRenderAutoScrolledForTest() {
        return lastRenderAutoScrolled;
    }

    /** Test-only (S15): the toggle of one view, for {@code doClick} and selection assertions. */
    JToggleButton presetToggleForTest(CommLogPreset preset) {
        return presetToggles.get(preset);
    }

    /** Test-only (S15): the Raw switch. */
    JCheckBox rawToggleForTest() {
        return rawToggle;
    }

    /**
     * Test-only (S15): what a mouse press on the first rendered occurrence of {@code ruleId} does.
     * A real press cannot be simulated headless (the caret's own mouse handling reaches for the
     * system selection), so this enters after the hit test. {@code false} when no such id is
     * rendered — because the row is filtered out, or the id is not a link at all.
     */
    boolean clickRuleForTest(String ruleId) {
        for (RuleRefs.Ref span : ruleSpans) {
            if (span.ruleId().equals(ruleId)) {
                showRule(span.ruleId());
                return true;
            }
        }
        return false;
    }

    /**
     * Test-only (S15): the hit test a mouse press and the hand cursor both use — the rule id drawn
     * under {@code point} (text-pane coordinates), if any. Empty while the pane has no size.
     */
    Optional<RuleRefs.Ref> ruleSpanAtForTest(Point2D point) {
        return spanAt(point);
    }

    /** Test-only (S15): the rule line's current text. */
    String ruleLineTextForTest() {
        return ruleLine.getText();
    }

    /** Test-only (S15): the rule ids of the current rendering, with their document offsets. */
    List<RuleRefs.Ref> ruleSpansForTest() {
        return ruleSpans;
    }

    /** Test-only (S15): the text pane's whole text, exactly as the document holds it. */
    String visibleTextForTest() {
        StyledDocument doc = textPane.getStyledDocument();
        try {
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }
}
