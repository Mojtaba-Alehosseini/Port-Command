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
 * ({@code tutorial-map}/{@code -chat}/{@code -commlog}/{@code -hud}, set by {@code MainWindow}); the
 * cutout is recomputed from the live component bounds every paint, so it tracks resizes and never
 * drifts. A target that isn't showing (e.g. minimised, or a Hint button with no open tab) simply
 * yields no cutout — the scrim dims fully and the caption still explains the step.
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
     * <p>Step 1's wording was corrected on 2026-07-27 (audit C-05): it used to say "vessels arrive
     * and move here as the day runs", but {@code VesselArrivedEvent}/{@code VesselDepartedEvent}
     * have no production publisher — {@code MapPanel}'s arrival/departure chips are wired,
     * consumed and tested, and nothing ever fires them. What the map genuinely shows is berth
     * occupancy (a vessel appears once a terminal grants and reports its berth), the four tug dots
     * and the weather chip, so that is what the text now promises. Wiring the two publishers is in
     * the backlog; until then the text must not point the player at something that cannot render.
     * {@code tutorial.json}'s own step-1 string was corrected in the same pass. */
    private static final String[] STEP_TEXT = {
            "This is the map — berths fill as vessels dock, and the four tugs move as they escort.",
            "When a walk-in vessel hails you, its negotiation opens as a chat tab here.",
            "Press the Hint button in a chat tab to ask the Assistant for a recommended counter-offer.",
            "Every agent message appears in the comm log here, colour-coded by FIPA performative.",
            "Your wallet, reputation and the day clock are up here in the HUD — watch them against the daily target.",
    };

    /** Component name each step highlights; steps 2 and 3 both point at the chat area (the Hint
     * button lives inside it, and has no stable single component to name across tabs). */
    private static final String[] STEP_TARGET = {
            MainWindow.NAME_MAP, MainWindow.NAME_CHAT, MainWindow.NAME_CHAT,
            MainWindow.NAME_COMMLOG, MainWindow.NAME_HUD,
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

    private boolean ready;
    private int currentStep; // 1..TOTAL_STEPS while shown; 0 when never shown

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
        closeButton.addActionListener(e -> dismiss());
        closeButton.setToolTipText("Dismiss the tutorial (reopen from Help → Show tutorial)");

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.setOpaque(false);
        controls.add(stepIndicator);
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

        this.autoDismissTimer = new Timer(autoDismissMillis, e -> dismiss());
        this.autoDismissTimer.setRepeats(false);

        this.readySubscription = eventBus.subscribe(GuiReadyEvent.class, e -> {
            ready = true;
            repaint();
        }, DeliveryMode.EDT);
        this.stepSubscription = eventBus.subscribe(TutorialStepAdvancedEvent.class,
                e -> showStep(e.step(), e.text()), DeliveryMode.EDT);
        // Audit C-13 (2026-07-27): the scrim must not outlive the world it explains. Switching
        // Tutorial -> Storm mid-demo (DEMO_SCRIPT Beat 5) otherwise left the tutorial's step-5
        // card and its 60% scrim sitting over the storm. ChatPanel/CommLogPanel already reset on
        // this event; the overlay was one of the three surfaces that did not.
        this.loadedSubscription = eventBus.subscribe(GameLoadedEvent.class, e -> dismiss(), DeliveryMode.EDT);
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
        caption.setText("<html><div style='width:320px'>" + escape(
                scripted ? scenarioText : STEP_TEXT[step - 1]) + "</div></html>");
        stepIndicator.setText("Step " + step + " of " + TOTAL_STEPS);
        nextButton.setText(step == TOTAL_STEPS ? "Done" : "Next");
        card.setVisible(true);
        setVisible(true);
        layoutCard();
        repaint();

        // Audit C-03 (2026-07-27) — the scripted driver had no ENDING. `tutorial.json` fires
        // exactly five TutorialStepAdvance events, the last at simTimeSeconds 1500, and there is
        // no sixth; `advance()` (the only other route to dismiss()) fires solely on the Next/Done
        // BUTTON, which is the MANUAL driver's ending. So a scenario-driven tutorial parked itself
        // on step 5 forever: at 1800 real-seconds/day all five steps land inside the first ~31 real
        // seconds, and the 60% black scrim then covered the map, chat and comm log for the whole
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
            dismiss();
        } else {
            showStep(currentStep + 1, null);
        }
    }

    private void dismiss() {
        autoDismissTimer.stop();
        card.setVisible(false);
        setVisible(false);
        currentStep = 0;
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
        boolean highlightInTopHalf = cut != null && cut.y + cut.height / 2 < h / 2;
        int y = highlightInTopHalf ? Math.max(20, h - cardH - 24) : 24;
        card.setBounds(x, y, cardW, cardH);
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
