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

import it.unige.portcommand.gui.events.GuiReadyEvent;
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
 * <p><b>Two drivers, one view.</b> The tutorial scenario fires {@link TutorialStepAdvancedEvent} on
 * the bus at scripted sim-times (this replaces the task-23 placeholder notification banner, keeping
 * that event contract); the overlay shows that step with the scenario's own text. Independently,
 * Help → "Show tutorial" (and first launch) calls {@link #startFromBeginning()} and the player
 * pages through the five canonical steps with Next. X dismisses at any time.
 *
 * <p><b>Non-blocking</b> (planning "common mistake"): {@link #contains(int, int)} is solid only over
 * the caption card, so every click OUTSIDE it — including on the highlighted element — passes
 * through to the app beneath. <b>Paint-safe</b>: nothing is drawn until {@link #isReady()} (backed
 * by {@link GuiReadyEvent} — the window is on screen and component bounds are valid), and the two
 * subscriptions are cancelled in {@link #removeNotify()} so a disposed window leaks neither.
 */
public final class TutorialOverlay extends JComponent {

    /** §3.13 canonical step texts — used for Help/first-launch paging (the scenario supplies its
     * own per-step text when it drives). */
    private static final String[] STEP_TEXT = {
            "This is the map — vessels arrive and move here as the day runs.",
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

    private final transient Container searchRoot;
    private final Subscription<GuiReadyEvent> readySubscription;
    private final Subscription<TutorialStepAdvancedEvent> stepSubscription;

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

        this.readySubscription = eventBus.subscribe(GuiReadyEvent.class, e -> {
            ready = true;
            repaint();
        }, DeliveryMode.EDT);
        this.stepSubscription = eventBus.subscribe(TutorialStepAdvancedEvent.class,
                e -> showStep(e.step(), e.text()), DeliveryMode.EDT);
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
        currentStep = step;
        caption.setText("<html><div style='width:320px'>" + escape(
                scenarioText != null && !scenarioText.isBlank() ? scenarioText : STEP_TEXT[step - 1])
                + "</div></html>");
        stepIndicator.setText("Step " + step + " of " + TOTAL_STEPS);
        nextButton.setText(step == TOTAL_STEPS ? "Done" : "Next");
        card.setVisible(true);
        setVisible(true);
        layoutCard();
        repaint();
    }

    private void advance() {
        if (currentStep >= TOTAL_STEPS) {
            dismiss();
        } else {
            showStep(currentStep + 1, null);
        }
    }

    private void dismiss() {
        card.setVisible(false);
        setVisible(false);
        currentStep = 0;
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
        readySubscription.cancel();
        stepSubscription.cancel();
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
