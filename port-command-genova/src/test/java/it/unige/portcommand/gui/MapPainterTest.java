package it.unige.portcommand.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

import it.unige.portcommand.agents.BerthOccupancy;
import it.unige.portcommand.agents.TugStatus;
import it.unige.portcommand.artifacts.PortStateUpdate;
import it.unige.portcommand.gui.events.WeatherChangeEvent;
import it.unige.portcommand.gui.model.MapModel;
import it.unige.portcommand.harbourmaster.BerthPositions;
import it.unige.portcommand.harbourmaster.TugBasePositions;
import it.unige.portcommand.ontology.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshot-style test: paints a seeded {@link MapModel} to a {@link BufferedImage} and samples
 * pixels at the REAL painted panel coordinates via {@link MapPainter#fit(int, int)} — NOT at raw
 * world coordinates. The on-screen berth-clipping bug lived precisely in the world→panel layer, so
 * a test that samples world coordinates directly (as the first version did) checks the wrong layer
 * and passes while the screen is wrong; these tests go through the same transform the painter does.
 */
class MapPainterTest {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 450;

    private static BufferedImage paint(MapModel model, int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            MapPainter.paint(g, w, h, model);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Colour at a WORLD point, mapped through the same fit transform the painter used. */
    private static Color sample(BufferedImage image, MapPainter.Transform t, double worldX, double worldY) {
        return new Color(image.getRGB(t.px(worldX), t.py(worldY)));
    }

    /** A world point clear of every berth (x 365-535), the tug-base zone (x 20-90), and the tug
     * dots (x ~50) — plain harbour background regardless of panel size. */
    private static Color background(BufferedImage image, MapPainter.Transform t) {
        return sample(image, t, 300, 120);
    }

    @Test
    void allFourBerthsRenderFullyOnPanelAtARealisticSmallSize() {
        // 460x300 is narrower than the old 1:1 painter could fit -- berth_2/berth_4 (world x=500)
        // used to clip off the right edge here. Every berth rectangle must now map fully on-panel.
        int w = 460;
        int h = 300;
        MapModel model = new MapModel();
        BufferedImage image = paint(model, w, h);
        MapPainter.Transform t = MapPainter.fit(w, h);

        for (String berthId : BerthPositions.all().keySet()) {
            Position pos = BerthPositions.position(berthId);
            int leftPx = t.px(pos.x() - MapPainter.BERTH_WIDTH / 2.0);
            int rightPx = t.px(pos.x() + MapPainter.BERTH_WIDTH / 2.0);
            int topPx = t.py(pos.y() - MapPainter.BERTH_HEIGHT / 2.0);
            int bottomPx = t.py(pos.y() + MapPainter.BERTH_HEIGHT / 2.0);
            assertTrue(leftPx >= 0 && rightPx < w,
                    berthId + " rectangle must fit horizontally on-panel (left=" + leftPx + " right=" + rightPx + " w=" + w + ")");
            assertTrue(topPx >= 0 && bottomPx < h,
                    berthId + " rectangle must fit vertically on-panel");
            assertNotEquals(background(image, t), sample(image, t, pos.x(), pos.y()),
                    berthId + " must render a visible rectangle at its centre");
        }
    }

    @Test
    void dockedBerthRendersADifferentColourThanFree() {
        MapModel model = new MapModel();
        MapPainter.Transform t = MapPainter.fit(WIDTH, HEIGHT);
        Position berth1 = BerthPositions.position("berth_1");
        Color freePixel = sample(paint(model, WIDTH, HEIGHT), t, berth1.x(), berth1.y());

        model.applyBerthDelta(new PortStateUpdate.BerthDelta("berth_1",
                new BerthOccupancy("berth_1", "V1", 0L, 1000L, 1, BerthOccupancy.Status.DOCKED)));
        Color dockedPixel = sample(paint(model, WIDTH, HEIGHT), t, berth1.x(), berth1.y());

        assertNotEquals(freePixel, dockedPixel, "DOCKED must render a visibly different colour than FREE");
    }

    @Test
    void idleTugsAreSeededAndRenderAtTheirBasesFromBoot() {
        MapModel model = new MapModel(); // no live deltas applied
        BufferedImage image = paint(model, WIDTH, HEIGHT);
        MapPainter.Transform t = MapPainter.fit(WIDTH, HEIGHT);

        // A base-zone pixel with no tug on it (world (80,90): inside the zone x20-90/y80-270, well
        // clear of the tug column at x=50) vs. tug_1's base centre (50,100). If the seed dot renders
        // on top of the zone, the two colours differ; if seeding were removed, both would be the
        // plain zone colour.
        Position base1 = TugBasePositions.position("tug_1");
        assertNotEquals(sample(image, t, 80, 90), sample(image, t, base1.x(), base1.y()),
                "a seeded IDLE tug dot must render on top of the base zone at boot");
    }

    @Test
    void noTugRendersAwayFromTheBasesUntilADeltaMovesOneThere() {
        MapModel model = new MapModel();
        BufferedImage image = paint(model, WIDTH, HEIGHT);
        MapPainter.Transform t = MapPainter.fit(WIDTH, HEIGHT);

        assertEquals(background(image, t), sample(image, t, 300, 300),
                "with only base seeds, an isolated spot away from the pier must be plain background");
    }

    @Test
    void aLiveTugDeltaMovesTheDotToItsNewPosition() {
        MapModel model = new MapModel();
        model.applyTugDelta(new PortStateUpdate.TugDelta("tug_1", new Position(300, 300, 0), TugStatus.ESCORTING));

        BufferedImage image = paint(model, WIDTH, HEIGHT);
        MapPainter.Transform t = MapPainter.fit(WIDTH, HEIGHT);

        assertNotEquals(background(image, t), sample(image, t, 300, 300),
                "a live tug delta must render the dot at its new position");
    }

    @Test
    void weatherChipRendersNearTheTopRightCorner() {
        MapModel model = new MapModel();
        model.applyWeather(new WeatherChangeEvent(40.0, "poor", 3.0, "stormy", true));

        BufferedImage image = paint(model, WIDTH, HEIGHT);
        MapPainter.Transform t = MapPainter.fit(WIDTH, HEIGHT);
        Color chipAreaPixel = new Color(image.getRGB(WIDTH - 15, 15));

        assertNotEquals(background(image, t), chipAreaPixel, "the weather chip must render near the top-right corner");
    }

    // S9: the arrival-chip burst case was DELETED with its event and painter arm (audit C-05's
    // dead render path — no production publisher ever existed; the decision was remove, not wire).

    // ==================== S12 (live playtest F-6): the berth label ====================
    //
    // The playtest saw berth_1 read "berth_1 (C50": the one-line "berth_1 (C501)" (~70 px at
    // 10 pt) ran out of its rectangle, which is 70 world units wide (42 px on the narrowest map).
    // The label is now two lines, berth id then occupant id, each fitted to the rectangle.

    /** Map sizes the panel really takes: MainWindow's 380 px floor, the 460x300 case above, half
     * of the 1280x800 window (~627x685, the demo layout) and MapPanel's preferred 800x450. */
    private static final int[][] MAP_SIZES = {{380, 600}, {460, 300}, {627, 685}, {800, 450}};
    /** Occupant ids the game produces: scripted spawns, a day-qualified contract, walk-ins. */
    private static final List<String> VESSEL_IDS =
            List.of("C501", "T901", "BD503", "WALKIN-S1", "WALKIN-12", "C001-D30", "STORM-W1");

    /** The metrics paintBerths draws with on a BufferedImage: the same derived font and hints. */
    private static FontMetrics labelMetrics() {
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            return g.getFontMetrics(g.getFont().deriveFont(Font.PLAIN, MapPainter.LABEL_FONT_SIZE));
        } finally {
            g.dispose();
        }
    }

    /** The width paintBerths gives each label line: the painted rectangle minus both insets. */
    private static int labelRoom(MapPainter.Transform t) {
        return t.len(MapPainter.BERTH_WIDTH) - 2 * MapPainter.LABEL_PAD;
    }

    @Test
    void everyBerthLabelLineFitsTheRectangleWidthAtEveryMapSize() {
        FontMetrics fm = labelMetrics();
        for (int[] size : MAP_SIZES) {
            int room = labelRoom(MapPainter.fit(size[0], size[1]));
            for (String berthId : BerthPositions.all().keySet()) {
                for (String vesselId : VESSEL_IDS) {
                    List<String> lines = MapPainter.berthLabelLines(berthId, vesselId, fm, room);
                    assertEquals(2, lines.size(), "berth id, then occupant id: " + lines);
                    for (String line : lines) {
                        assertTrue(fm.stringWidth(line) <= room, size[0] + "x" + size[1] + ": '" + line
                                + "' is " + fm.stringWidth(line) + " px against " + room + " px of room");
                    }
                }
            }
        }
    }

    @Test
    void bothLabelLinesFitTheRectangleHeightFrom460x300Up() {
        FontMetrics fm = labelMetrics();
        for (int[] size : new int[][] {{460, 300}, {627, 685}, {800, 450}}) {
            MapPainter.Transform t = MapPainter.fit(size[0], size[1]);
            int height = t.len(MapPainter.BERTH_HEIGHT);
            List<String> lines = MapPainter.berthLabelLines("berth_1", "WALKIN-12", fm, labelRoom(t));
            int needed = MapPainter.LABEL_PAD + lines.size() * fm.getHeight();
            assertTrue(needed <= height, size[0] + "x" + size[1] + ": " + lines.size() + " lines need "
                    + needed + " px, the rectangle is " + height + " px tall");
        }
    }

    @Test
    void nothingIsCutInTheDemoLayout() {
        FontMetrics fm = labelMetrics();
        int room = labelRoom(MapPainter.fit(627, 685));
        for (String berthId : BerthPositions.all().keySet()) {
            assertEquals(List.of(berthId), MapPainter.berthLabelLines(berthId, null, fm, room),
                    "a free berth shows its id alone");
            for (String vesselId : VESSEL_IDS) {
                assertEquals(List.of(berthId, vesselId), MapPainter.berthLabelLines(berthId, vesselId, fm, room));
            }
        }
    }

    @Test
    void theOldOneLineLabelOverflowsTheRectangleAt460x300() {
        // Non-vacuity for the size checks above: the pre-S12 label really did not fit here.
        FontMetrics fm = labelMetrics();
        int room = labelRoom(MapPainter.fit(460, 300));
        int oldWidth = fm.stringWidth("berth_1 (C501)");
        assertTrue(oldWidth > room, "the old label is " + oldWidth + " px against " + room + " px of room");
    }

    @Test
    void fitToWidthCutsWithAnEllipsisOnlyWhenTheTextDoesNotFit() {
        FontMetrics fm = labelMetrics();
        String id = "WALKIN-12";
        int width = fm.stringWidth(id);

        assertEquals(id, MapPainter.fitToWidth(id, fm, width), "exactly as wide as the room: unchanged");

        String cut = MapPainter.fitToWidth(id, fm, width - 1);
        assertTrue(cut.length() > 1 && cut.startsWith("\u2026"),
                "one pixel short: cut with an ellipsis, got '" + cut + "'");
        assertTrue(id.endsWith(cut.substring(1)),
                "the cut keeps the id's distinguishing tail: " + cut);
        assertTrue(fm.stringWidth(cut) <= width - 1, "and fits: " + fm.stringWidth(cut) + " px");

        assertEquals("", MapPainter.fitToWidth(id, fm, 0), "no room at all: nothing");
    }

    @Test
    void distinctOccupantsStayDistinctWhenTheirLabelsAreCut() {
        // Review finding (S12): cutting the HEAD of the id rendered busy_day's WALKIN-B1 and
        // WALKIN-B3 both as "WALKIN\u2026" on a narrow map. The fitted lines must stay distinct.
        FontMetrics fm = labelMetrics();
        List<String> ids = List.of("WALKIN-B1", "WALKIN-B2", "WALKIN-B3", "WALKIN-S1", "WALKIN-12",
                "C001-D29", "C001-D30", "C501", "T901", "BD503", "STORM-W1", "STORM-C1", "STORM-C2");
        for (int[] size : MAP_SIZES) {
            int room = labelRoom(MapPainter.fit(size[0], size[1]));
            List<String> fitted = ids.stream().map(id -> MapPainter.fitToWidth(id, fm, room)).toList();
            assertEquals(ids.size(), new java.util.HashSet<>(fitted).size(),
                    size[0] + "x" + size[1] + " (" + room + " px): two occupants read the same: " + fitted);
        }
    }

    @Test
    void anOccupiedBerthsLabelStaysInsideItsRectangle() {
        // At 460x300 the old label ran ~70 px from x+3 against a 52 px rectangle, so its text
        // landed in the sea gap before berth_2 whatever the (per-run) paint order. The occupant
        // is one that MUST be cut at this size (review finding: with "C501" nothing was cut, so
        // this test could not see paintBerths skip the fitting or pass it the wrong width).
        int w = 460;
        int h = 300;
        String occupant = "WALKIN-12";
        MapModel model = new MapModel();
        model.applyBerthDelta(new PortStateUpdate.BerthDelta("berth_1",
                new BerthOccupancy("berth_1", occupant, 0L, 1000L, 1, BerthOccupancy.Status.DOCKED)));
        BufferedImage image = paint(model, w, h);
        MapPainter.Transform t = MapPainter.fit(w, h);
        Color sea = background(image, t);

        Position berth1 = BerthPositions.position("berth_1");
        Position berth2 = BerthPositions.position("berth_2");
        double halfW = MapPainter.BERTH_WIDTH / 2.0;
        double halfH = MapPainter.BERTH_HEIGHT / 2.0;
        // 2 px in from each rectangle keeps the anti-aliased outlines out of the check.
        int gapLeft = t.px(berth1.x() + halfW) + 2;
        int gapRight = t.px(berth2.x() - halfW) - 2;
        assertTrue(gapRight > gapLeft, "the sea gap must be measurable at this size");
        for (int px = gapLeft; px <= gapRight; px++) {
            for (int py = t.py(berth1.y() - halfH); py <= t.py(berth1.y() + halfH); py++) {
                assertEquals(sea, new Color(image.getRGB(px, py)),
                        "label ink in the sea gap at (" + px + ", " + py + ")");
            }
        }

        // Non-vacuity: the gap is clean because the label is drawn INSIDE the rectangle, not
        // because it is gone. The occupant's line must carry ink; the lower half of its glyph
        // bodies lies below anything line 1 can draw.
        FontMetrics fm = labelMetrics();
        assertTrue(fm.stringWidth(occupant) > labelRoom(t), "setup: the occupant id must need cutting here");
        int x = t.px(berth1.x() - halfW);
        int y = t.py(berth1.y() - halfH);
        Color fill = new Color(image.getRGB(x + t.len(MapPainter.BERTH_WIDTH) - 3,
                y + t.len(MapPainter.BERTH_HEIGHT) - 3));
        int baseline2 = y + MapPainter.LABEL_PAD + fm.getAscent() + fm.getHeight();
        int textEnd = x + MapPainter.LABEL_PAD + fm.stringWidth(MapPainter.fitToWidth(occupant, fm, labelRoom(t)));
        boolean ink = false;
        for (int px = x + MapPainter.LABEL_PAD; px < textEnd; px++) {
            for (int py = baseline2 - fm.getAscent() / 2; py < baseline2; py++) {
                ink |= !fill.equals(new Color(image.getRGB(px, py)));
            }
        }
        assertTrue(ink, "the occupant id must render as the label's second line, inside the rectangle");

        // And no ink in the rectangle's right inset either: the one check that catches the label
        // being fitted to the whole rectangle width instead of the room inside both insets.
        int insetColumn = x + t.len(MapPainter.BERTH_WIDTH) - 2;
        for (int py = y + 2; py <= y + t.len(MapPainter.BERTH_HEIGHT) - 2; py++) {
            assertEquals(fill, new Color(image.getRGB(insetColumn, py)),
                    "label ink in the rectangle's right inset at (" + insetColumn + ", " + py + ")");
        }
    }
}
