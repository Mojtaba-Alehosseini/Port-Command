package it.unige.portcommand.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Test helper (S15, 2026-10-12): laying a Swing component tree out and painting it with no window.
 *
 * <p>Both Gradle test lanes run with {@code -Djava.awt.headless=true}, so no test can realise a
 * {@code JFrame}; but a {@code JPanel} tree needs no window to be given a size, laid out and
 * painted into an image — which is how {@code GameScreenCompositionTest} measures the three
 * columns and {@code GameScreenRenderTest} produces the PNG files under {@code build/gui-renders}.
 *
 * <p><b>Why not {@code validate()}.</b> {@code Container.validate()} lays a tree out only when the
 * container has a native peer ({@code if (!isValid() && peer != null)} — JDK 21); a component that
 * was never put in a window has none, so {@code validate()} returns having done nothing and every
 * child keeps its 0×0 bounds. {@link #layOut} therefore calls {@code doLayout()} itself, top down.
 */
final class HeadlessSwing {

    private HeadlessSwing() {
    }

    /** A block of test code run on the EDT; may throw, and may fail assertions. */
    @FunctionalInterface
    interface EdtBody {
        void run() throws Exception;
    }

    /**
     * Runs {@code body} on the EDT and waits for it. What it throws comes out of this call as
     * itself — an assertion failure stays an {@code AssertionError} with its message, instead of
     * being reported as the {@code InvocationTargetException} that {@code invokeAndWait} wraps it
     * in.
     */
    static void onEdt(EdtBody body) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    body.run();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Exception e) {
                    throw new CheckedFailure(e);
                }
            });
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CheckedFailure checked) {
                throw (Exception) checked.getCause();
            }
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    /** Lets every EDT-delivered bus event published so far be handled before the test goes on. */
    static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** Carries a checked exception out of the EDT body; unwrapped again by {@link #onEdt}. */
    private static final class CheckedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CheckedFailure(Exception cause) {
            super(cause);
        }
    }

    /**
     * Gives {@code root} the size {@code width}×{@code height} (keeping its position) and lays the
     * whole tree under it out — what showing it in a window of that size would do. EDT only.
     *
     * <p>Two passes: a layout manager sizes a child from the child's preferred size, and for some
     * children (wrapped text, a scroll pane deciding whether it needs a scroll bar) that preferred
     * size depends on the width the first pass gave them. A real window reaches the same state
     * through the revalidation those components ask for. A split pane that has been told where
     * its divider goes keeps that location through both passes.
     */
    static void layOut(JComponent root, int width, int height) {
        root.setSize(width, height);
        layOutTree(root);
        layOutTree(root);
    }

    private static void layOutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layOutTree(nested);
            }
        }
    }

    /**
     * Paints {@code component}, at its current size, into a new RGB image over a white ground.
     * The component must have been laid out ({@link #layOut}); a zero-sized one cannot be painted.
     * EDT only.
     */
    static BufferedImage paint(JComponent component) {
        if (component.getWidth() <= 0 || component.getHeight() <= 0) {
            throw new IllegalStateException("lay the component out before painting it: " + component.getSize());
        }
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            component.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** The first component under {@code root} (depth first, {@code root} itself excluded) whose
     * name is {@code name}, or {@code null}. */
    static Component byName(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container nested) {
                Component found = byName(nested, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** {@code component}'s bounds in {@code ancestor}'s coordinate space — its position on the
     * screen being tested rather than inside whatever panel happens to hold it. */
    static Rectangle boundsIn(Component component, Component ancestor) {
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), ancestor);
    }

    /** The sum of the red, green and blue values of every pixel of {@code area} — a plain
     * measure of how light a patch of an image is, for "this patch got darker" assertions. */
    static long brightness(BufferedImage image, Rectangle area) {
        Rectangle inside = area.intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        long sum = 0;
        for (int y = inside.y; y < inside.y + inside.height; y++) {
            for (int x = inside.x; x < inside.x + inside.width; x++) {
                int rgb = image.getRGB(x, y);
                sum += ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
            }
        }
        return sum;
    }
}
