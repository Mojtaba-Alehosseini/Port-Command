package it.unige.portcommand.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 (2026-10-12): the harbour picture is gone from the game — its panel, its painter and its
 * model were deleted, and the Port table took its place in the left column. This is the gate
 * that keeps it gone: nothing under {@code src/main} or {@code src/test} may name one of the
 * three deleted classes, the component name the tutorial used to highlight the picture by, or the
 * constant that held that name.
 *
 * <p>Why a test and not just the compiler. A leftover REFERENCE would not compile; a leftover
 * MENTION does — in a javadoc, a comment, a string, a resource file — and it then sends the next
 * reader looking for a class that no longer exists, or (the component name) leaves the tutorial
 * pointing at nothing, which fails silently: an overlay step whose target is missing just dims
 * the whole window. Prose that says "the map" in the past tense is deliberately not hunted; only
 * these five exact tokens are.
 *
 * <p><b>The searched-for tokens are spelled in two halves below</b> and joined by the compiler, so
 * that this file — which lives in one of the trees it searches — does not contain them. For the
 * same reason nothing in this file's comments may spell one out.
 */
class NoMapLeftTest {

    /** Relative to the Gradle project directory, which is the test JVM's working directory (the
     * same assumption {@code PortTableModelTest}'s source check makes). */
    private static final List<Path> SOURCE_TREES = List.of(Path.of("src", "main"), Path.of("src", "test"));

    private static final String PREFIX = "Map";

    /** The three deleted classes, the old highlight target's component name, and its constant. */
    private static final List<String> FORBIDDEN = List.of(
            PREFIX + "Panel",
            PREFIX + "Painter",
            PREFIX + "Model",
            "tutorial-" + "map",
            "NAME_" + "MAP");

    /** Every regular file under the two source trees, whatever its type. */
    private static List<Path> sourceFiles() throws IOException {
        List<Path> all = new ArrayList<>();
        for (Path tree : SOURCE_TREES) {
            assertTrue(Files.isDirectory(tree), "expected a source tree at " + tree.toAbsolutePath()
                    + " — is the test running from the Gradle project directory?");
            try (Stream<Path> walk = Files.walk(tree)) {
                all.addAll(walk.filter(Files::isRegularFile).sorted().toList());
            }
        }
        return all;
    }

    /**
     * Every line of every source file that contains {@code needle}, as {@code path:line: text}.
     * Files are decoded as ISO-8859-1: every byte sequence is valid in it, so no file type can
     * make the read fail, and an all-ASCII needle is found in a UTF-8 file just the same.
     */
    private static List<String> linesContaining(String needle) throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path file : sourceFiles()) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            if (!text.contains(needle)) {
                continue;
            }
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(needle)) {
                    hits.add(file + ":" + (i + 1) + ": " + lines[i].strip());
                }
            }
        }
        return hits;
    }

    @Test
    void nothingInEitherSourceTreeNamesTheRemovedClassesOrTheirTutorialTarget() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String token : FORBIDDEN) {
            hits.addAll(linesContaining(token));
        }

        assertTrue(hits.isEmpty(), "the removed harbour picture is still named in " + hits.size()
                + " place(s):\n  " + String.join("\n  ", hits));
    }

    @Test
    void theFiveDeletedFilesHaveNotComeBack() {
        Path mainGui = Path.of("src", "main", "java", "it", "unige", "portcommand", "gui");
        Path testGui = Path.of("src", "test", "java", "it", "unige", "portcommand", "gui");
        List<Path> deleted = List.of(
                mainGui.resolve(PREFIX + "Panel.java"),
                mainGui.resolve(PREFIX + "Painter.java"),
                mainGui.resolve("model").resolve(PREFIX + "Model.java"),
                testGui.resolve(PREFIX + "PainterTest.java"),
                testGui.resolve("model").resolve(PREFIX + "ModelTest.java"));

        for (Path file : deleted) {
            assertFalse(Files.exists(file), file + " was deleted in S15 and must stay deleted");
        }
        assertTrue(Files.isDirectory(mainGui) && Files.isDirectory(testGui),
                "fixture: the two gui source directories are where this test looks for them");
    }

    @Test
    void theSearchReallyReadsTheSourceTrees() throws IOException {
        // Non-vacuity for the gate above: the same search must be able to FIND something. It finds
        // the class that replaced the picture's panel in the layout, in main and in test code —
        // and it sees a few hundred files, not an empty directory.
        List<String> hits = linesContaining("class Game" + "Screen");

        assertTrue(sourceFiles().size() > 100, "only " + sourceFiles().size() + " files were searched");
        assertTrue(hits.stream().anyMatch(hit -> hit.contains("main") && hit.contains("GameScreen.java")),
                "the search did not find GameScreen's own declaration: " + hits);
        assertTrue(hits.stream().anyMatch(hit -> hit.contains("test") && hit.contains("GameScreenCompositionTest")),
                "the search did not reach the test tree: " + hits);
    }
}
