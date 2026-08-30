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

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The dry run's verdict, in the form a human reads before deciding to let the bot start.
 *
 * <pre>
 * PLAN READY   15004 / 15004 cells proven   4 scaffold blocks   ETA 2h41m
 * </pre>
 *
 * <p>or, and this is the case the design is actually for:
 *
 * <pre>
 * PLAN INCOMPLETE   4231 / 15004 cells proven
 *   blocked at 112,-57,93  minecraft:piston[facing=down]
 *   reason: no stance yields the required look
 *   evaluated: 243 candidate stances -&gt; 194 not standable, 49 nothing solid to click, 0 workable
 *   the 3 cells that would unblock it: 111,-57,93 / 113,-57,93 / 112,-57,92
 * </pre>
 *
 * <p>Learning in seconds that the build stops at cell 4 231 — and which three cells would unblock it — is worth more
 * than three hours of a bot discovering the same thing one cell at a time. Every blocker is reported at once rather
 * than the first one, because otherwise each run teaches you exactly one cell instead of the size of the problem.
 *
 * <p>Everything the engine cannot do is named here, before the run, not discovered during it: blocks with no item
 * form, iron doors and trapdoors whose {@code open} no click can reach, chest contents and entities, real
 * waterlogging, and any material shortfall the ledger predicts.
 *
 * @param status      READY or INCOMPLETE
 * @param plan        the plan, present even when INCOMPLETE — the prefix that WOULD run is itself information
 * @param cellsTotal  schematic cells in scope
 * @param proven      cells whose dependency footprint lay entirely in loaded chunks
 * @param provisional cells resting on terrain the snapshot never saw
 * @param blockers    every cell the planner could not solve, not just the first
 * @param tight       cells solvable only below {@link SolveBudget#MIN_MARGIN}, with their numbers
 * @param layers      per-layer cell and scaffold counts, in build order
 * @param materials   what the build consumes, including replacements for lossy breaks
 * @param limitations the named exceptions above; each one is a promise kept in advance rather than a surprise
 */
public record PlanReport(
        Status status,
        BuildPlan plan,
        int cellsTotal,
        int proven,
        int provisional,
        List<Blocker> blockers,
        List<TightCell> tight,
        List<LayerSummary> layers,
        List<MaterialNeed> materials,
        List<String> limitations
) {

    public PlanReport {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(plan, "plan");
        blockers = List.copyOf(blockers);
        tight = List.copyOf(tight);
        layers = List.copyOf(layers);
        materials = List.copyOf(materials);
        limitations = List.copyOf(limitations);
        // A READY report that carries blockers is a lie in the only line anybody reads, and it is the line that
        // decides whether a three-hour run starts. The reverse is merely unhelpful, so it is not refused here.
        if (status == Status.READY && !blockers.isEmpty()) {
            throw new IllegalArgumentException("a READY plan cannot have " + blockers.size() + " blocked cells");
        }
    }

    /** Line separator for every rendered artefact. Hard {@code \n}, never {@link System#lineSeparator()}: two runs of
     *  the same build must produce the same file byte for byte, and that must not depend on the operating system. */
    private static final String NL = "\n";

    public enum Status {

        /** Every cell in scope has a proven or provisional solution and the order closes. */
        READY,

        /** At least one cell has no solution. The build does not start; {@link #blockers} says which and why. */
        INCOMPLETE
    }

    /**
     * A cell the planner could not solve, explained by name.
     *
     * @param dominantReason the rejection that accounted for most candidates — the headline of the "reason:" line
     * @param rejections     the full tally, indexed by {@link PlacementOracle.Rejection#ordinal()}
     * @param stancesTried   how many candidate stances were evaluated before giving up
     * @param unblocking     cells whose placement would give this one a solution, when the planner can name them.
     *                       The most actionable line in the whole report: it turns "it is stuck" into a decision
     * @param detail         free text for the cases the enum cannot express, e.g. the upward family's missing foot
     *                       column in all eight of its candidate columns
     */
    public record Blocker(
            BlockPos cell,
            BlockState desired,
            PlacementOracle.Rejection dominantReason,
            int[] rejections,
            int stancesTried,
            List<BlockPos> unblocking,
            String detail
    ) {

        public Blocker {
            Objects.requireNonNull(cell, "cell");
            rejections = rejections == null ? new int[0] : rejections.clone();
            unblocking = List.copyOf(unblocking);
        }

        /** A copy, because an array component of a record is otherwise a hole straight through its immutability, and
         *  this one is read by the report, the log line and the bench in the same run. */
        @Override
        public int[] rejections() {
            return this.rejections.clone();
        }
    }

    /** A cell that has a solution, but only under the acceptance margin. Reported with the number rather than taken
     *  silently, because a margin of 0.02 blocks is a coin flip and the report is where a coin flip gets declared. */
    public record TightCell(BlockPos cell, BlockState desired, double bestMargin) {
    }

    /**
     * One layer's line in the report.
     *
     * @param scaffolds every helper block this layer places, each naming the cell it serves — the report reads
     *                  {@code scaffold 112,-56,93 (schematic cell of layer -56) serves 112,-57,93}
     */
    public record LayerSummary(int layer, int cells, int scaffoldBlocks, List<ScaffoldNote> scaffolds) {

        public LayerSummary {
            scaffolds = List.copyOf(scaffolds);
        }
    }

    /** A single planned helper block: where it goes, which cell it exists for, and which layer's schematic cell it
     *  temporarily occupies (or {@link Integer#MIN_VALUE} when it occupies no schematic cell at all). */
    public record ScaffoldNote(BlockPos scaffold, BlockPos serves, int occupiesSchematicLayer) {

        /** {@link #occupiesSchematicLayer} for a helper block that stands in empty space — not in any schematic
         *  cell, so no later layer has to wait for it to come back out. */
        public static final int NO_SCHEMATIC_CELL = Integer.MIN_VALUE;
    }

    /**
     * How much of one item the build needs.
     *
     * @param forReplacement extra units needed because a break loses its block — glass drops nothing, and finding
     *                       that out mid-run is exactly the class of surprise this report exists to remove
     * @param available      what the inventory snapshot holds; less than {@code needed} is a limitation line, not a
     *                       reason to start and hope
     */
    public record MaterialNeed(Item item, int needed, int forReplacement, int available) {

        /** How many units the build is short. Zero when the snapshot covers it. */
        public int shortfall() {
            return Math.max(0, this.needed - this.available);
        }
    }

    /** The block the report leads with — READY or INCOMPLETE plus the headline numbers. */
    public String headline() {
        if (this.status == Status.READY) {
            StringBuilder head = new StringBuilder(String.format(Locale.ROOT,
                    "PLAN READY   %d / %d cells proven   %d scaffold blocks   ETA %s",
                    this.proven, this.cellsTotal, this.plan.counts().scaffoldBlocks(), this.plan.eta()));
            // A plan whose cells rest on terrain the snapshot never saw is still READY, but saying so without saying
            // how much of it was guessed would make PROVEN and PROVISIONAL the same word in the only line read.
            if (this.provisional > 0) {
                head.append(String.format(Locale.ROOT, "   %d provisional (terrain not loaded)", this.provisional));
            }
            return head.toString();
        }
        // The count of blocked cells is the size of the problem, and it belongs in the first line — but only once
        // there is more than one, because "1 cells blocked" above a report that then names that one cell is noise.
        String scale = this.blockers.size() > 1
                ? String.format(Locale.ROOT, "   %d cells blocked", this.blockers.size()) : "";
        return String.format(Locale.ROOT, "PLAN INCOMPLETE   %d / %d cells proven%s",
                this.proven, this.cellsTotal, scale);
    }

    /** The whole report as text, for {@code run/plan/<runid>-report.txt} and for {@code logMechanic}. */
    public String render() {
        StringBuilder out = new StringBuilder(headline());

        // Blockers first and all of them. A report that stops at the first blocked cell teaches one cell per run,
        // and the runs cost hours; this is the entire reason the dry run exists.
        for (Blocker blocker : this.blockers) {
            out.append(NL).append("  blocked at ").append(BuildAction.describePos(blocker.cell()))
                    .append("  ").append(BuildAction.describeState(blocker.desired()));
            out.append(NL).append("  reason: ").append(reasonText(blocker.dominantReason()));
            out.append(NL).append("  evaluated: ").append(blocker.stancesTried())
                    .append(" candidate stances -> ").append(tally(blocker.rejections()));
            out.append(NL).append("  ").append(unblockingText(blocker.unblocking()));
            if (blocker.detail() != null && !blocker.detail().isBlank()) {
                out.append(NL).append("  detail: ").append(blocker.detail());
            }
        }

        if (!this.tight.isEmpty()) {
            out.append(NL).append(String.format(Locale.ROOT, "tight cells (margin under %.2f): %d",
                    SolveBudget.MIN_MARGIN, this.tight.size()));
            for (TightCell cell : this.tight) {
                out.append(NL).append(String.format(Locale.ROOT, "  %s  %s  margin %.2f",
                        BuildAction.describePos(cell.cell()), BuildAction.describeState(cell.desired()),
                        cell.bestMargin()));
            }
        }

        for (LayerSummary layer : this.layers) {
            out.append(NL).append(String.format(Locale.ROOT, "layer %d:  %d cells,  %d scaffold blocks",
                    layer.layer(), layer.cells(), layer.scaffoldBlocks()));
            for (ScaffoldNote note : layer.scaffolds()) {
                String occupies = note.occupiesSchematicLayer() == ScaffoldNote.NO_SCHEMATIC_CELL
                        ? "(no schematic cell)"
                        : String.format(Locale.ROOT, "(schematic cell of layer %d)", note.occupiesSchematicLayer());
                out.append(NL).append("  scaffold ").append(BuildAction.describePos(note.scaffold()))
                        .append("  ").append(occupies)
                        .append("  serves ").append(BuildAction.describePos(note.serves()));
            }
        }

        if (!this.materials.isEmpty()) {
            out.append(NL).append("materials:");
            for (MaterialNeed need : this.materials) {
                out.append(NL).append(String.format(Locale.ROOT, "  %-34s needed %d",
                        BuildAction.describeItem(need.item()), need.needed()));
                if (need.forReplacement() > 0) {
                    out.append(String.format(Locale.ROOT, "  (%d replacing lossy breaks)", need.forReplacement()));
                }
                out.append(String.format(Locale.ROOT, "  available %d", need.available()));
                if (need.shortfall() > 0) {
                    out.append(String.format(Locale.ROOT, "   SHORT by %d", need.shortfall()));
                }
            }
        }

        if (!this.limitations.isEmpty()) {
            out.append(NL).append("limitations:");
            for (String limitation : this.limitations) {
                out.append(NL).append("  - ").append(limitation);
            }
        }
        return out.toString();
    }

    public boolean isReady() {
        return this.status == Status.READY;
    }

    /**
     * The "reason:" line — one sentence per rejection, in the words the plan document uses.
     *
     * <p>No {@code default} branch on purpose. A rejection added to the oracle without a sentence here would
     * otherwise print as an enum constant or as nothing at all, in the one line whose job is to tell a human what to
     * do next; this way it does not compile until somebody has said what it means.
     */
    private static String reasonText(PlacementOracle.Rejection reason) {
        if (reason == null) {
            return "no candidate survived, and the planner could not name a dominant cause";
        }
        return switch (reason) {
            case OUT_OF_BOUNDS -> "the cell lies outside the captured snapshot";
            case UNLOADED_CHUNK -> "the snapshot never saw this cell's surroundings";
            case CELL_OCCUPIED -> "the cell holds a block the plan does not break";
            case NO_ITEM -> "no item on the hotbar places this block";
            case STANCE_NOT_STANDABLE -> "no candidate stance is standable";
            case APPROACH_NOT_SUPPORTED -> "the stance is standable, but the concrete approach or its fine-movement "
                    + "corridor has no collision surface at the proven feet height";
            case POST_ACTION_NOT_SUPPORTED -> "this click would remove or reshape the collision surface under the "
                    + "bot before it can leave the stance";
            case STANCE_FILTERED -> "every stance was vetoed by the rejection journal";
            case NOTHING_SOLID_TO_CLICK -> "no face of the cell has anything solid to click against";
            case BODY_INTERSECTS_TARGET -> "the bot's own body occupies the cell from every stance";
            case EYE_INSIDE_BLOCK -> "the crouched eye sits inside a block from every stance";
            case OUT_OF_REACH -> "every stance is out of reach of the click point";
            case RAY_MISSED_FACE -> "the ray reaches a different block or a different face";
            case RAY_OBSTRUCTED -> "something stands between the eye and the click point";
            case LOOK_NOT_DOMINANT -> "no stance yields the required look";
            case MARGIN_TOO_TIGHT -> "the only solutions are under the acceptance margin";
            case WRONG_STATE_WOULD_LAND -> "the click would land a state the acceptance rules refuse";
            case FOLLOWUP_UNREACHABLE -> "the block would land, but its required bucket follow-up cannot be aimed";
            case CANNOT_SURVIVE -> "the block cannot survive at this cell";
            case SPACE_OBSTRUCTED -> "the cell's own volume is obstructed";
            case RAY_BUDGET_EXHAUSTED -> "the ray budget ran out before an answer";
            case STANCE_BUDGET_EXHAUSTED -> "the stance cap was reached before these candidates were tried";
            case OCCLUSION_NOT_ROBUST -> "the sight line clears from the exact stance point but not from the whole "
                    + "area the bot may arrive in";
            case OCCLUSION_BELOW_BODY_FLOOR -> "the sight line clears from the exact stance point, and holding the "
                    + "bot that still is beyond the fine approach: it walks in eight fixed directions at a sneak "
                    + "step, so it cannot settle inside " + BuildAction.describeTolerance(
                            FineApproach.ACHIEVABLE_TOLERANCE) + " blocks. Clear whatever the ray grazes, or build "
                    + "this cell before its neighbour";
        };
    }

    /**
     * The "evaluated:" tally — {@code 194 not standable, 49 nothing solid to click, 0 workable}.
     *
     * <p>Ordered by count descending with the enum order as tiebreak, so the same plan renders the same line twice,
     * and zero counts are dropped: a list of eighteen reasons of which sixteen are zero hides the two that matter.
     * The closing {@code 0 workable} is a constant — a cell with a workable stance is not a blocker — and it is
     * printed anyway, because it is the term that turns a list of failures into a total.
     */
    private static String tally(int[] rejections) {
        PlacementOracle.Rejection[] reasons = PlacementOracle.Rejection.values();
        List<Integer> ordinals = new ArrayList<>();
        for (int ordinal = 0; ordinal < Math.min(rejections.length, reasons.length); ordinal++) {
            if (rejections[ordinal] > 0) {
                ordinals.add(ordinal);
            }
        }
        ordinals.sort((first, second) -> rejections[first] != rejections[second]
                ? Integer.compare(rejections[second], rejections[first]) : Integer.compare(first, second));
        StringBuilder out = new StringBuilder();
        for (int ordinal : ordinals) {
            out.append(rejections[ordinal]).append(' ').append(tallyLabel(reasons[ordinal])).append(", ");
        }
        return out.append("0 workable").toString();
    }

    /** The short form of a rejection, for the tally. Exhaustive for the same reason {@link #reasonText} is. */
    private static String tallyLabel(PlacementOracle.Rejection reason) {
        return switch (reason) {
            case OUT_OF_BOUNDS -> "out of bounds";
            case UNLOADED_CHUNK -> "chunk not loaded";
            case CELL_OCCUPIED -> "cell occupied";
            case NO_ITEM -> "no item";
            case STANCE_NOT_STANDABLE -> "not standable";
            case APPROACH_NOT_SUPPORTED -> "approach not supported";
            case POST_ACTION_NOT_SUPPORTED -> "own click removes stance support";
            case STANCE_FILTERED -> "vetoed by the journal";
            case NOTHING_SOLID_TO_CLICK -> "nothing solid to click";
            case BODY_INTERSECTS_TARGET -> "body in the way";
            case EYE_INSIDE_BLOCK -> "eye inside a block";
            case OUT_OF_REACH -> "out of reach";
            case RAY_MISSED_FACE -> "ray missed the face";
            case RAY_OBSTRUCTED -> "line of sight blocked";
            case LOOK_NOT_DOMINANT -> "look not dominant";
            case MARGIN_TOO_TIGHT -> "margin too tight";
            case WRONG_STATE_WOULD_LAND -> "wrong state would land";
            case FOLLOWUP_UNREACHABLE -> "bucket follow-up unreachable";
            case CANNOT_SURVIVE -> "cannot survive";
            case SPACE_OBSTRUCTED -> "space obstructed";
            case RAY_BUDGET_EXHAUSTED -> "ray budget exhausted";
            case STANCE_BUDGET_EXHAUSTED -> "stance cap reached, never evaluated";
            case OCCLUSION_NOT_ROBUST -> "sight line not robust over the approach tolerance";
            case OCCLUSION_BELOW_BODY_FLOOR -> "would need a tighter approach than the body can hold";
        };
    }

    /** The most actionable line in the report, and the one that decides what a human does next. Its absence is
     *  information too — no cell unblocking a piston means the foot column is missing, and scaffold adds blocks
     *  where a hole is what is needed. */
    private static String unblockingText(List<BlockPos> unblocking) {
        if (unblocking.isEmpty()) {
            return "no placement of another cell would unblock it";
        }
        StringBuilder out = new StringBuilder(unblocking.size() == 1
                ? "the cell that would unblock it: "
                : "the " + unblocking.size() + " cells that would unblock it: ");
        for (int index = 0; index < unblocking.size(); index++) {
            out.append(index == 0 ? "" : " / ").append(BuildAction.describePos(unblocking.get(index)));
        }
        return out.toString();
    }
}
