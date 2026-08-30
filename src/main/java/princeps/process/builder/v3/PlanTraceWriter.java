/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princeps is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Princeps.  If not, see <https://www.gnu.org/licenses/>.
 */

package princeps.process.builder.v3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts the frozen plan on disk, next to the build it belongs to.
 *
 * <p>Three files per run, because three different questions get asked of a build and answering them from one file
 * meant reconstructing the other two by hand:
 * <pre>
 *   plan/&lt;runid&gt;-plan.txt     one line per action, in the frozen order
 *   plan/&lt;runid&gt;-proof.txt    one line per cell: stance, approach, against, face, aim point, margin
 *   plan/&lt;runid&gt;-report.txt   the dry run's verdict, or every blocking cell by name
 * </pre>
 *
 * <p>The point of writing them at all is that "why is cell 4231 placed after cell 4230" becomes a {@code grep} rather
 * than a reconstruction. That was the single most expensive missing piece of information in the previous engine: the
 * order was decided inside a scan that left no record, so any question about it could only be answered by re-deriving
 * the scan in your head.
 *
 * <p>The path is {@code plan/}, NOT {@code run/plan/}. The client's working directory already IS {@code run/} — which
 * is why {@code latest.log} lives at {@code run/logs/latest.log} and {@code BuildTrace} opens {@code logs/}. Getting
 * this wrong once put a trace in {@code run/run/logs} and cost a directory listing to find.
 *
 * <p>Like {@link princeps.process.builder.BuildTrace}, nothing here may take a build down: an unwritable disk is a
 * diagnostic loss, not a build failure. Every method returns null instead of throwing. It deliberately does NOT catch
 * {@code RuntimeException}, so an unimplemented renderer surfaces as the bug it is rather than as a silently missing
 * file.
 */
public final class PlanTraceWriter {

    /** Relative to the client's working directory, which is {@code run/}. See the class javadoc. */
    private static final String DIRECTORY = "plan";

    private PlanTraceWriter() {}

    /**
     * The run identifier the three files are named after.
     *
     * <p>{@code princeps.bench.run} first, so a bench run's plan sits under the same id its verdict line and its
     * {@code BuildTrace} carry and the three can be joined without guessing. No timestamp: two runs of the same input
     * must produce byte-identical output, and a clock in the file name would defeat the reproducibility check that is
     * this engine's sharpest regression test.
     */
    public static String runId(String buildName) {
        String raw = System.getProperty("princeps.bench.run", buildName == null || buildName.isEmpty()
                ? "build" : buildName);
        String safe = raw.replaceAll("[^A-Za-z0-9._-]", "-");
        return safe.isEmpty() ? "build" : safe;
    }

    /** Where the three files go. Created on demand; null if it could not be created. */
    public static Path directory() {
        Path dir = Paths.get(DIRECTORY);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return null;
        }
        return dir;
    }

    /** The frozen order, one line per action. @return the file written, or null */
    public static Path writePlan(String runId, BuildPlan plan) {
        return write(runId + "-plan.txt", plan.toPlanLines());
    }

    /** One line per cell: everything the oracle proved about it. @return the file written, or null */
    public static Path writeProof(String runId, BuildPlan plan) {
        return write(runId + "-proof.txt", plan.toProofLines());
    }

    /** The dry run's verdict. @return the file written, or null */
    public static Path writeReport(String runId, PlanReport report) {
        return write(runId + "-report.txt", List.of(report.render().split("\n", -1)));
    }

    /**
     * All three, in one call, at the end of the dry run.
     *
     * <p>Partial output is kept on purpose: a report that names a blocker is worth having even when there is no plan
     * to go with it, which is exactly the INCOMPLETE case.
     *
     * @return the files actually written, in plan/proof/report order, never null
     */
    public static List<Path> writeAll(String runId, BuildPlan plan, PlanReport report) {
        List<Path> written = new ArrayList<>(3);
        if (plan != null) {
            addIfPresent(written, writePlan(runId, plan));
            addIfPresent(written, writeProof(runId, plan));
        }
        if (report != null) {
            addIfPresent(written, writeReport(runId, report));
        }
        return written;
    }

    private static void addIfPresent(List<Path> into, Path path) {
        if (path != null) {
            into.add(path);
        }
    }

    private static Path write(String fileName, List<String> lines) {
        Path dir = directory();
        if (dir == null) {
            return null;
        }
        Path file = dir.resolve(fileName);
        try {
            Files.write(file, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            return null;
        }
        return file;
    }
}
