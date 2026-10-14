package it.unige.portcommand.gui;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import it.unige.portcommand.gui.events.GuiReadyEvent;
import it.unige.portcommand.persistence.events.GameLoadedEvent;
import it.unige.portcommand.scenario.events.TutorialStepAdvancedEvent;
import it.unige.portcommand.util.DeliveryMode;
import it.unige.portcommand.util.EventBus;
import it.unige.portcommand.util.Subscription;

/**
 * The five-step tutorial overlay (task 21, planning/21 §21.3; reconciled 2026-07-18). A
 * translucent scrim over the content area that cuts a bright hole around one UI element and shows
 * a caption card explaining it. Mounted in {@code MainWindow}'s {@code JLayeredPane} (the glass
 * pane is already the {@code PauseOverlay}), sized to the content pane and kept in sync on resize.
 *
 * <p><b>Highlight by component NAME, not pixels</b> (planning risk note): each step names a target
 * ({@code tutorial-port}/{@code -chat}/{@code -commlog}/{@code -hud}); the cutout is recomputed
 * from the live component bounds every paint, so it tracks resizes and never drifts. A target that
 * isn't showing (e.g. minimised, or a Hint button with no open tab) simply yields no cutout — the
 * scrim dims fully and the caption still explains the step. The name constants, and the components
 * that carry them, are {@link GameScreen}'s since S15 (2026-10-12), when the Port table became the
 * first step's target.
 *
 * <p><b>Two drivers, one view — and two different endings.</b> The tutorial scenario fires
 * {@link TutorialStepAdvancedEvent} on the bus at scripted sim-times (this replaces the task-23
 * placeholder notification banner, keeping that event contract); the overlay shows that step with
 * the scenario's own text, and dismisses itself {@link #SCRIPTED_AUTO_DISMISS_MILLIS} after the
 * FINAL scripted step, because a script has no Done button to click (audit C-03). Independently,
 * Help → "Show tutorial" (and first launch) calls {@link #startFromBeginning()} and the player
 * pages through the five canonical steps with Next, ending on Done. X dismisses at any time, and
 * so does a world swap ({@code GameLoadedEvent}).
 *
 * <p><b>Non-blocking</b> (planning "common mistake"): {@link #contains(int, int)} is solid only over
 * the caption card, so every click OUTSIDE it — including on the highlighted element — passes
 * through to the app beneath. <b>Paint-safe</b>: nothing is drawn until {@link #isReady()} (backed
 * by {@link GuiReadyEvent} — the window is on screen and component bounds are valid), and the two
 * subscriptions are cancelled in {@link #removeNotify()} so a disposed window leaks neither.
 */
public final class TutorialOverlay extends JComponent {

    /** §3.13 canonical step texts — used for Help/first-launch paging (the scenario supplies its
     * own per-step text when it drives).
     *
     * <p><b>Step 1 describes the Port table (S15, 2026-10-12)</b>, which replaced the picture of
     * the harbour the step used to point at: what a berth line says (who holds it, until when)
     * and what the lines under the berths count. It says only what the table really shows — the
     * rule this step has been held to since audit C-05 (2026-07-27), when its text promised
     * vessel movements nothing ever drew. It deliberately says nothing about the "Waiting for you"
     * list above the table, and no step text anywhere may: that list's number is the walk-in's
     * time counter, which is never labelled or explained (INVARIANTS). {@code tutorial.json}'s
     * step-1 sentence was reworded in the same session. */
    private static final String[] STEP_TEXT = {
            "This is the Port table — each berth shows the vessel holding it and until when; "
                    + "the lines below count the queue and the tugs.",
            "When a walk-in vessel hails you, its negotiation opens as a chat tab here.",
            "Press the Hint button in a chat tab to ask the Assistant for a recommended counter-offer.",
            "Every agent message appears in the comm log here, colour-coded by FIPA performative.",
            "Your wallet, reputation and the day clock are up here in the HUD — watch them against the daily target.",
    };

    /** Component name each step highlights; steps 2 and 3 both point at the chat area (the Hint
     * button lives inside it, and has no stable single component to name across tabs). */
    private static final String[] STEP_TARGET = {
            GameScreen.NAME_PORT, GameScreen.NAME_CHAT, GameScreen.NAME_CHAT,
            GameScreen.NAME_COMMLOG, GameScreen.NAME_HUD,
    };

    static final int TOTAL_STEPS = 5;

    /**
     * How long the FINAL scripted step stays up before the overlay dismisses itself (audit C-03,
     * 2026-07-27). Long enough to read the last card; short enough to be gone before the demo's
     * second beat. In {@code tutorial.json} the five steps fire at 0/200/600/1100/1500 sim-seconds
     * and the scenario runs at 1800 real-seconds/day, so step 5 lands ~31 real seconds in and the
     * scrim clears ~43 s in — inside Beat 1's two-minute budget.
     */
    static final int SCRIPTED_AUTO_DISMISS_MILLIS = 12_000;

    private final transient Container searchRoot;
    private final Subscription<GuiReadyEvent> readySubscription;
    private final Subscription<TutorialStepAdvancedEvent> stepSubscription;
    private final Subscription<GameLoadedEvent> loadedSubscription;
    /** One-shot, armed only by the SCRIPTED driver's final step; see {@link #showStep}. */
    private final transient Timer autoDismissTimer;

    private final JPanel card = new JPanel(new BorderLayout(8, 8));
    private final JLabel caption = new JLabel();
    private final JLabel stepIndicator = new JLabel();
    private final JButton nextButton = new JButton("Next");
    private final JButton closeButton = new JButton("×");
    /** S18b (PT18): the second way out, in words. */
    private final JButton skipButton = new JButton("Skip tutorial");

    private boolean ready;
    private int currentStep; // 1..TOTAL_STEPS while shown; 0 when never shown
    /** S18b (playtest 3-4, PT1/PT18): the player closed the tutorial - ×, Skip, or Done on the last
     * step. Nothing more of it shows in this game; a new world starts it afresh. */
    private boolean closedForGame;
    /** S18b (PT1): the highest scripted step shown in this game - each step is shown once. */
    private int shownUpTo;
    /** S18b (PT18): whether the card on screen came from the scenario script. */
    private boolean scriptedCard;
    /** S18b (PT18): scripted steps that arrived while a card was being read - shown in turn, one
     * per "Got it", instead of pulling the card from under the reader. */
    private final java.util.TreeMap<Integer, String> waitingSteps = new java.util.TreeMap<>();
    /** S18b (P10): told when the player ends the tutorial, so the window can remember it. */
    private transient Runnable onClosedForGood = () -> { };

    /**
     * @param eventBus   the shared bus (GuiReady + scripted tutorial steps)
     * @param searchRoot the content pane whose subtree is searched for the named highlight targets
     */
    public TutorialOverlay(EventBus eventBus, Container searchRoot) {
        this(eventBus, searchRoot, SCRIPTED_AUTO_DISMISS_MILLIS);
    }

    /** Test seam: {@code autoDismissMillis} lets a headless test assert the scripted auto-dismiss
     * without waiting the production dwell. Production uses the two-arg constructor. */
    TutorialOverlay(EventBus eventBus, Container searchRoot, int autoDismissMillis) {
        this.searchRoot = searchRoot;
        setOpaque(false);
        setVisible(false);
        setLayout(null); // the caption card is positioned manually relative to the cutout

        caption.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        stepIndicator.setForeground(new Color(90, 90, 90));
        nextButton.addActionListener(e -> advance());
        closeButton.addActionListener(e -> closeForGood());
        closeButton.setToolTipText("End the tutorial (reopen it from Help → Show tutorial)");
        skipButton.addActionListener(e -> closeForGood());
        skipButton.setToolTipText("End the tutorial (reopen it from Help → Show tutorial)");

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.setOpaque(false);
        controls.add(stepIndicator);
        controls.add(skipButton);
        controls.add(nextButton);
        controls.add(closeButton);
        card.setBackground(new Color(250, 250, 250));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 214, 10), 2),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        card.add(caption, BorderLayout.CENTER);
        card.add(controls, BorderLayout.SOUTH);
        card.setVisible(false);
        add(card);

        this.autoDismissTimer = new Timer(autoDismissMillis, e -> closeForGood()); // the last card: over
        this.autoDismissTimer.setRepeats(false);

        this.readySubscription = eventBus.subscribe(GuiReadyEvent.class, e -> {
            ready = true;
            repaint();
        }, DeliveryMode.EDT);
        this.stepSubscription = eventBus.subscribe(TutorialStepAdvancedEvent.class,
                e -> onScriptedStep(e.step(), e.text()), DeliveryMode.EDT);
        // Audit C-13 (2026-07-27): the scrim must not outlive the world it explains. Switching
        // Tutorial -> Storm mid-demo (DEMO_SCRIPT Beat 5) otherwise left the tutorial's step-5
        // card and its 60% scrim sitting over the storm. ChatPanel/CommLogPanel already reset on
        // this event; the overlay was one of the three surfaces that did not.
        this.loadedSubscription = eventBus.subscribe(GameLoadedEvent.class, e -> resetForANewGame(),
                DeliveryMode.EDT);
    }

    /** S18b (P10): what to run when the player ends the tutorial (the window remembers it). */
    public void onClosedForGood(Runnable action) {
        this.onClosedForGood = action == null ? () -> { } : action;
    }

    /**
     * S18b (playtest 3-4, PT1/PT18): a step the scenario script fires. It is dropped once the
     * player has closed the tutorial in this game, and when it was already shown; while another
     * card is being read it waits for that card's "Got it" - the cards used to change under the
     * reader on the script's clock, and come back after Close.
     */
    private void onScriptedStep(int step, String text) {
        if (closedForGame || step < 1 || step > TOTAL_STEPS || step <= shownUpTo) {
            return;
        }
        if (isShowingAStep()) {
            waitingSteps.put(step, text);
            return;
        }
        showStep(step, text);
    }

    /** Help → "Show tutorial" / first launch: page from step 1 with the canonical text. */
    public void startFromBeginning() {
        showStep(1, null);
    }

    /** True once the window is on screen ({@link GuiReadyEvent}) OR the overlay is displayable — so
     * nothing paints against not-yet-valid component bounds. */
    public boolean isReady() {
        return ready || isDisplayable();
    }

    private void showStep(int step, String scenarioText) {
        if (step < 1 || step > TOTAL_STEPS) {
            return;
        }
        boolean scripted = scenarioText != null && !scenarioText.isBlank();
        currentStep = step;
        scriptedCard = scripted;
        if (scripted) {
            shownUpTo = Math.max(shownUpTo, step);
        }
        caption.setText("<html><div style='width:320px'>" + escape(
                scripted ? scenarioText : STEP_TEXT[step - 1]) + "</div></html>");
        stepIndicator.setText("Step " + step + " of " + TOTAL_STEPS);
        // S18b (PT18): a scripted card is read, not paged - "Got it" puts it away until the
        // script's next step (or shows a step that arrived meanwhile).
        nextButton.setText(step == TOTAL_STEPS ? "Done" : scripted ? "Got it" : "Next");
        card.setVisible(true);
        setVisible(true);
        layoutCard();
        repaint();

        // Audit C-03 (2026-07-27) — the scripted driver had no ENDING. `tutorial.json` fires
        // exactly five TutorialStepAdvance events, the last at simTimeSeconds 1500, and there is
        // no sixth; `advance()` (the only other route to dismiss()) fires solely on the Next/Done
        // BUTTON, which is the MANUAL driver's ending. So a scenario-driven tutorial parked itself
        // on step 5 forever: at 1800 real-seconds/day all five steps land inside the first ~31 real
        // seconds, and the 60% black scrim then covered every panel under it for the whole
        // rest of the session — including the storm beat and the EOD report. Clicks still passed
        // through (contains() is solid only over the card), so it was purely, and severely, visual.
        // Only the SCRIPTED path auto-dismisses: Help -> "Show tutorial" is the player paging at
        // their own speed and must still wait for Done.
        autoDismissTimer.stop();
        if (scripted && step == TOTAL_STEPS) {
            autoDismissTimer.restart();
        }
    }

    private void advance() {
        if (currentStep >= TOTAL_STEPS) {
            closeForGood(); // Done
            return;
        }
        if (scriptedCard) {
            java.util.Map.Entry<Integer, String> next = waitingSteps.pollFirstEntry();
            while (next != null && next.getKey() <= shownUpTo) {
                next = waitingSteps.pollFirstEntry();
            }
            if (next == null) {
                hideCard();
            } else {
                showStep(next.getKey(), next.getValue());
            }
            return;
        }
        showStep(currentStep + 1, null);
    }

    /** Puts the card away; the tutorial goes on (a later scripted step may show). */
    private void hideCard() {
        autoDismissTimer.stop();
        card.setVisible(false);
        setVisible(false);
        currentStep = 0;
    }

    /** S18b (PT1/PT18/P10): ×, Skip, Done on the last step, or the last card's own timer - no
     * more tutorial in this game, and the window is told so it can remember it. */
    private void closeForGood() {
        hideCard();
        closedForGame = true;
        waitingSteps.clear();
        onClosedForGood.run();
    }

    /** A new world (a new game or a load) gets its own tutorial; nothing of the last one stays. */
    private void resetForANewGame() {
        hideCard();
        closedForGame = false;
        shownUpTo = 0;
        waitingSteps.clear();
    }

    /**
     * Package-private probe for the headless C-03 test: the overlay is "up" when a step is showing.
     *
     * <p><b>This is the dismiss STATE MACHINE, not the scrim.</b> {@link #paintComponent}
     * additionally gates on {@link #isReady()} and a non-zero size, and headlessly {@code isReady()}
     * is false and the size is 0×0, so nothing paints in the tests at all. Neither condition implies
     * the other, and an earlier version of this javadoc claimed they could not drift — they can.
     * What the tests genuinely pin is the timer/dismiss logic, which IS the C-03 defect; whether the
     * scrim is visible on a real window belongs to the GUI walk. (Adversarial review, 2026-07-27.)
     */
    boolean isShowingAStep() {
        return currentStep != 0 && isVisible();
    }

    /** Package-private test seam for the Next/Done button, so a headless test can page the MANUAL
     * driver without synthesising an {@code ActionEvent} on a button that is never realized. */
    void advanceForTest() {
        advance();
    }

    /** S18b: the × button, as a click would press it. */
    void closeForTest() {
        closeButton.doClick();
    }

    /** S18b: the "Skip tutorial" button, as a click would press it. */
    void skipForTest() {
        skipButton.doClick();
    }

    /** S18b: the caption on the card now (HTML). */
    String captionForTest() {
        return caption.getText();
    }

    /** S18b: the label of the paging button now (Next / Got it / Done). */
    String nextLabelForTest() {
        return nextButton.getText();
    }

    /** Test-only (S15): the canonical text of step {@code step} (1..{@link #TOTAL_STEPS}) — what
     * Help → "Show tutorial" and the first-launch cards say. */
    static String canonicalStepTextForTest(int step) {
        return STEP_TEXT[step - 1];
    }

    /** Test-only (S15): the component name step {@code step} (1..{@link #TOTAL_STEPS}) highlights,
     * so a test can check that the real screen has a component by that name. */
    static String stepTargetNameForTest(int step) {
        return STEP_TARGET[step - 1];
    }

    /**
     * Click-through: solid ONLY over the caption card (so its Next/× buttons work); every other
     * point — including inside the highlight, so the player can actually click the element being
     * pointed at — reports false and the event falls through to the app beneath.
     */
    @Override
    public boolean contains(int x, int y) {
        return isReady() && isVisible() && card.isVisible() && card.getBounds().contains(x, y);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        if (!isReady() || currentStep == 0 || w <= 0 || h <= 0) {
            return; // never paint before the window is up / while dismissed / at zero size
        }
        // Dim (SRC_OVER 0.6) then punch the highlight hole (DST_OUT) — planning/21 §21.3. Composed
        // in an explicit ARGB buffer so DST_OUT has a real alpha channel to clear, rather than
        // depending on the (non-opaque, layered-pane) component's own buffer being alpha-backed.
        BufferedImage layer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D lg = layer.createGraphics();
        lg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        lg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
        lg.setColor(Color.BLACK);
        lg.fillRect(0, 0, w, h);
        Rectangle cut = currentCutout();
        if (cut != null) {
            lg.setComposite(AlphaComposite.getInstance(AlphaComposite.DST_OUT));
            lg.setColor(Color.WHITE); // opaque source → clears the dim inside the hole
            lg.fillRoundRect(cut.x, cut.y, cut.width, cut.height, 14, 14);
        }
        lg.dispose();

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(layer, 0, 0, null);
        if (cut != null) {
            g2.setColor(new Color(255, 214, 10));
            g2.setStroke(new java.awt.BasicStroke(3f));
            g2.drawRoundRect(cut.x, cut.y, cut.width, cut.height, 14, 14);
        }
        g2.dispose();
    }

    /** The highlight rect in overlay-local coordinates, or null when the step's target isn't
     * on screen. Recomputed every paint so a resize/relayout can never leave it stale. */
    private Rectangle currentCutout() {
        if (currentStep == 0) {
            return null;
        }
        Component target = findByName(searchRoot, STEP_TARGET[currentStep - 1]);
        if (target == null || !target.isShowing() || target.getWidth() <= 0 || target.getHeight() <= 0) {
            return null;
        }
        Rectangle r = SwingUtilities.convertRectangle(target.getParent(), target.getBounds(), this);
        r.grow(3, 3);
        return r.intersection(new Rectangle(0, 0, getWidth(), getHeight()));
    }

    /** Positions the caption card clear of the cutout: opposite half from the highlight (top if the
     * highlight is low, bottom if high), horizontally centred and clamped on-screen. */
    private void layoutCard() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Dimension pref = card.getPreferredSize();
        int cardW = Math.min(Math.max(pref.width, 300), Math.max(200, w - 40));
        int cardH = Math.min(Math.max(pref.height, 90), Math.max(90, h - 40));
        int x = Math.max(20, (w - cardW) / 2);
        Rectangle cut = currentCutout();
        boolean topBar = currentStep > 0 && GameScreen.NAME_HUD.equals(STEP_TARGET[currentStep - 1]);
        card.setBounds(x, cardY(cut, topBar, h, cardH), cardW, cardH);
    }

    /**
     * Where the card goes. Clear of the highlight: the opposite half from it - except for the top
     * bar, where the card sits just UNDER the bar (S18b, PT18: at the bottom of the window it was
     * far from what it explains). Pure, so the placement is testable without a screen.
     */
    static int cardY(Rectangle cut, boolean targetIsTopBar, int overlayHeight, int cardHeight) {
        if (targetIsTopBar && cut != null) {
            return Math.min(cut.y + cut.height + 8, Math.max(20, overlayHeight - cardHeight - 24));
        }
        boolean highlightInTopHalf = cut != null && cut.y + cut.height / 2 < overlayHeight / 2;
        return highlightInTopHalf ? Math.max(20, overlayHeight - cardHeight - 24) : 24;
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        if (card.isVisible()) {
            layoutCard();
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        autoDismissTimer.stop();
        readySubscription.cancel();
        stepSubscription.cancel();
        loadedSubscription.cancel();
    }

    private static Component findByName(Container root, String name) {
        if (root == null) {
            return null;
        }
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container container) {
                Component found = findByName(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
