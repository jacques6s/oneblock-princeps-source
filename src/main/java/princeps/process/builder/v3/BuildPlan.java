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
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The frozen order — every action, in the sequence they will be executed, proven before the bot takes a step.
 *
 * <p>This is the artefact the whole design exists to produce. Once it is built the executor makes no decisions: take
 * action N, run it, advance. The reactive builder's root cause was choosing the next cell every tick from the current
 * pose against the current world, which commits to a cell before knowing whether it can be placed; here the choice
 * was made once, against a world simulated forward, and it is a proof rather than a heuristic.
 *
 * <p>Immutable and reproducible. No jitter, no randomness, no time-dependence anywhere in its construction: the same
 * schematic against the same world must produce the same plan byte for byte, and two runs of the same build must
 * produce the same tick count. That is the sharpest regression test this project can have, and it only works if
 * nothing in here is sampled. The compact constructor copies every collection for the same reason — a planner that
 * keeps a handle on the list it handed over could otherwise change the frozen order after it was frozen.
 *
 * @param name          the schematic's user-facing name
 * @param origin        where the schematic's local (0,0,0) sits in the world
 * @param actions       the frozen order, index equals execution position
 * @param layerOrder    layer Y values in execution order; the executor asserts the hard layer bound against it
 * @param proofByCell   per cell, keyed by {@link PlacementGeometry#positionKey}, why it is placeable and how
 * @param counts        the numbers the report and the verdict line print
 */
public record BuildPlan(
        String name,
        Vec3i origin,
        List<BuildAction> actions,
        List<Integer> layerOrder,
        Map<Long, CellProof> proofByCell,
        Counts counts
) {

    public BuildPlan {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(counts, "counts");
        actions = List.copyOf(actions);
        layerOrder = List.copyOf(layerOrder);
        proofByCell = Map.copyOf(proofByCell);
    }

    /**
     * Ticks one placement costs, walk included.
     *
     * <p>Measured rather than guessed: the walk alone is ~13 ticks per block and it is the second largest cost item
     * in a build, so a per-placement figure that leaves it out is not an estimate but a wish. 13 ticks per placement
     * is what the plan's own worked example comes to — 15 004 cells in 2h41m is 93 placements a minute — and it sits
     * deliberately below the 100–140 blocks/min the design expects, because an ETA that runs long is a nuisance and
     * an ETA that runs short is a lie.
     */
    public static final int TICKS_PER_PLACEMENT = 13;

    /** Ticks one break costs. Above a placement: the block has to be mined through, and the executor may not break
     *  and place in the same tick because {@code CLICK_LEFT} clears the pending {@code CLICK_RIGHT} commit. */
    public static final int TICKS_PER_BREAK = 24;

    /** Ticks per right-click of an {@link BuildAction.Interact}. No travel term: an interaction follows its own
     *  placement at the same stance, aim already converged, so all it pays is the click cadence. */
    public static final int TICKS_PER_INTERACTION_CLICK = 5;

    /** Ticks one sign costs — the {@code SignEditScreen} opens, four lines are typed, the screen is confirmed, and
     *  aim and movement are suspended for all of it. */
    public static final int TICKS_PER_SIGN = 60;

    /** Ticks one hotbar fetch costs. The expensive inventory operation: a container click, rate-limited, competing
     *  with {@code InventoryBehavior}'s own upkeep. This is the number Belady minimises the COUNT of. */
    public static final int TICKS_PER_HOTBAR_SWAP = 30;

    /** How certain the plan is about a cell. */
    public enum Confidence {

        /** The cell's entire dependency footprint — itself, its six neighbours, every candidate stance, every ray
         *  path, everything within reach plus one — lay in loaded chunks when the snapshot was taken. */
        PROVEN,

        /** Something in that footprint was not loaded, so the solution rests on terrain nobody has seen.
         *  Re-proven incrementally on approach; a failure there is a NAMED halt, never a silent fallback. */
        PROVISIONAL
    }

    /**
     * Why one cell is placeable, and how. Written to {@code run/plan/<runid>-proof.txt}, one line each.
     *
     * @param actionIndex where in {@link #actions} this cell's PLACE sits, so the proof and the order cross-reference
     */
    public record CellProof(
            BlockPos cell,
            BlockState desired,
            PlacementSolution solution,
            Confidence confidence,
            int layer,
            int actionIndex
    ) {

        public CellProof {
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(solution, "solution");
            Objects.requireNonNull(confidence, "confidence");
        }
    }

    /**
     * The plan's arithmetic.
     *
     * @param cells             schematic cells the plan covers
     * @param proven            cells at {@link Confidence#PROVEN}
     * @param provisional       cells at {@link Confidence#PROVISIONAL}
     * @param placements        {@link BuildAction.Place} actions
     * @param breaks            {@link BuildAction.Break} actions
     * @param scaffoldBlocks    {@link BuildAction.PlaceScaffold} actions; equal to the RemoveScaffold count, always,
     *                          because a helper block that is planned and not un-planned violates the layer bound
     * @param scaffoldByLayer   helper blocks per layer Y — six is a footnote, six hundred is a design problem, and
     *                          this is what makes the difference visible before the build runs
     * @param hotbarSwaps       {@link BuildAction.SwapHotbar} actions; the Belady optimum for this order, and
     *                          therefore also the ceiling on what any further material grouping could save
     * @param interactions      {@link BuildAction.Interact} actions
     * @param raysCast          rays the dry run spent, for the cost model
     * @param estimatedTicks    ETA, from walk distance plus per-action cost
     */
    public record Counts(
            int cells,
            int proven,
            int provisional,
            int placements,
            int breaks,
            int scaffoldBlocks,
            Map<Integer, Integer> scaffoldByLayer,
            int hotbarSwaps,
            int interactions,
            long raysCast,
            long estimatedTicks
    ) {

        public Counts {
            scaffoldByLayer = Map.copyOf(scaffoldByLayer);
        }
    }

    /** How many actions the plan holds. */
    public int size() {
        return this.actions.size();
    }

    // There used to be a prefix(int) here, which returned the first N actions with the proofs, the layer order and the
    // counts kept whole. Its only caller was the RUNNABLE PREFIX gate in PlannedBuilderProcess, which stopped the
    // order at the first action ActionRunner owned but nothing delegated to. CellExecutor.delegate closed that seam,
    // the gate went with it, and a shortened plan is now something this engine has no way to want: the frozen order
    // IS the unit of honesty, and the executor advances through it on confirmations rather than being handed a
    // smaller plan to finish. Reinstating this method would be the first half of reinstating the gate.

    /** The action at an execution index. */
    public BuildAction action(int index) {
        return this.actions.get(index);
    }

    /** The proof for a cell, or empty when the plan does not cover it. */
    public Optional<CellProof> proofFor(BlockPos cell) {
        return cell == null ? Optional.empty()
                : Optional.ofNullable(this.proofByCell.get(PlacementGeometry.positionKey(cell)));
    }

    /** Actions belonging to one layer, in order — what the executor checks the layer bound against. */
    public List<BuildAction> actionsInLayer(int layer) {
        List<BuildAction> inLayer = new ArrayList<>();
        for (BuildAction action : this.actions) {
            if (action.layer() == layer) {
                inLayer.add(action);
            }
        }
        return List.copyOf(inLayer);
    }

    /**
     * Index of the first action of a layer, or -1 when the plan has none.
     *
     * <p>A layer's actions are contiguous by construction — the hard layer rule means layer L is finished, scaffold
     * ledger included, before anything of L+1 is planned — so this and {@link #layerEnd} bracket exactly the block of
     * actions the gate at that boundary is responsible for.
     */
    public int layerStart(int layer) {
        for (int index = 0; index < this.actions.size(); index++) {
            if (this.actions.get(index).layer() == layer) {
                return index;
            }
        }
        return -1;
    }

    /** Index one past the last action of a layer, or -1 when the plan has none. Exclusive, so
     *  {@code layerStart(L)..layerEnd(L)} is the half-open range of that layer's block. */
    public int layerEnd(int layer) {
        for (int index = this.actions.size() - 1; index >= 0; index--) {
            if (this.actions.get(index).layer() == layer) {
                return index + 1;
            }
        }
        return -1;
    }

    /**
     * Is this index the first action of a new layer — the point at which the executor's hard gate fires?
     *
     * <p>The gate checks the LIVE world, not the bookkeeping: every schematic cell of the previous layer correct and
     * every helper block of it gone. Index 0 is a boundary too; the layer below it is empty, so the check passes
     * trivially and the executor needs no special case for the first action.
     */
    public boolean isLayerBoundary(int index) {
        if (index < 0 || index >= this.actions.size()) {
            return false;
        }
        return index == 0 || this.actions.get(index).layer() != this.actions.get(index - 1).layer();
    }

    /**
     * Helper blocks the plan places in this layer and has not yet removed at action index {@code upTo}. Must be
     * empty at the layer boundary; the bench audit independently checks the world for the same thing.
     *
     * <p>{@code upTo} is exclusive: every action strictly before it counts as executed, which is the state the
     * executor is in when it is about to run action {@code upTo}. Returned in placement order — removal runs in the
     * reverse of it, because a helper block placed later may be standing on one placed earlier.
     */
    public List<BlockPos> openScaffoldAt(int layer, int upTo) {
        // Keyed on BlockPos#asLong rather than on the positions themselves: a BetterBlockPos and a plain BlockPos
        // with identical coordinates hash differently and would leave a removed helper block on the ledger forever.
        // This key is local to the method and never mixed with proofByCell's PlacementGeometry#positionKey.
        LinkedHashMap<Long, BlockPos> open = new LinkedHashMap<>();
        int end = Math.min(upTo, this.actions.size());
        for (int index = 0; index < end; index++) {
            BuildAction action = this.actions.get(index);
            if (action instanceof BuildAction.PlaceScaffold placed) {
                if (placed.layer() == layer) {
                    open.put(placed.cell().asLong(), placed.cell());
                }
            } else if (action instanceof BuildAction.RemoveScaffold removed) {
                // Removals are honoured whatever layer they claim. A removal booked against the wrong layer is a
                // planner bug, but treating it as "still standing" would make the ledger unclosable and stall the
                // build at a gate that can never open — the louder failure is the wrong one here.
                open.remove(removed.cell().asLong());
            }
        }
        return List.copyOf(open.values());
    }

    /** Ticks the plan expects to take. */
    public long etaTicks() {
        return this.counts.estimatedTicks();
    }

    /** The ETA as the report prints it, e.g. {@code 2h41m}. */
    public String eta() {
        return formatEta(etaTicks());
    }

    /** The frozen order as text, one action per line, for {@code run/plan/<runid>-plan.txt}. */
    public List<String> toPlanLines() {
        List<String> lines = new ArrayList<>(this.actions.size() + 1);
        lines.add(String.format(Locale.ROOT, "# plan %s  origin %d,%d,%d  %d actions  %d cells  ETA %s",
                this.name, this.origin.getX(), this.origin.getY(), this.origin.getZ(),
                this.actions.size(), this.counts.cells(), eta()));
        for (int index = 0; index < this.actions.size(); index++) {
            BuildAction action = this.actions.get(index);
            lines.add(String.format(Locale.ROOT, "%06d  layer %4d  %s", index, action.layer(), action.describe()));
        }
        return List.copyOf(lines);
    }

    /**
     * The per-cell proof as text, one cell per line, for {@code run/plan/<runid>-proof.txt}.
     *
     * <p>Ordered by action index, so proof.txt and plan.txt line up and "why is cell 4 231 placed the way it is" is
     * two greps at the same number. The tiebreak is the cell's own coordinates rather than a hash, because a hash
     * order is stable only within one JVM run and the reproducibility rule is stricter than that.
     *
     * <p>The geometry is rendered through {@link BuildAction#describeSolution}, the same formatter the PLACE lines in
     * plan.txt use, so the two files can never disagree about a solution they both describe.
     */
    public List<String> toProofLines() {
        List<CellProof> ordered = new ArrayList<>(this.proofByCell.values());
        ordered.sort(Comparator.comparingInt(CellProof::actionIndex)
                .thenComparingInt(proof -> proof.cell().getY())
                .thenComparingInt(proof -> proof.cell().getX())
                .thenComparingInt(proof -> proof.cell().getZ()));
        List<String> lines = new ArrayList<>(ordered.size() + 1);
        lines.add(String.format(Locale.ROOT, "# proof %s  %d cells  %d proven  %d provisional",
                this.name, this.counts.cells(), this.counts.proven(), this.counts.provisional()));
        for (CellProof proof : ordered) {
            lines.add(String.format(Locale.ROOT, "%06d  %-11s layer %4d  %s  %s",
                    proof.actionIndex(), proof.confidence(), proof.layer(),
                    BuildAction.describePos(proof.cell()), BuildAction.describeSolution(proof.solution())));
        }
        return List.copyOf(lines);
    }

    /**
     * What the frozen order costs in ticks, from the actions alone.
     *
     * <p>Deterministic and count-based on purpose. A planner that has already computed the real walk distance between
     * consecutive stances may put a better number into {@link Counts#estimatedTicks}; this is the floor everything
     * else is compared against, and it is what makes an ETA available before the walk is known.
     */
    public static long estimateTicks(List<BuildAction> actions) {
        long ticks = 0L;
        for (BuildAction action : actions) {
            ticks += switch (action.kind()) {
                case PLACE, PLACE_SCAFFOLD, FILL_FLUID -> TICKS_PER_PLACEMENT;
                // A jump-place pays the placement plus the arc it has to wait out before the click.
                case JUMP_PLACE -> TICKS_PER_PLACEMENT + JumpArc.MAX_TICKS;
                case BREAK, REMOVE_SCAFFOLD -> TICKS_PER_BREAK;
                case INTERACT -> (long) TICKS_PER_INTERACTION_CLICK * ((BuildAction.Interact) action).clicks();
                case WRITE_SIGN -> TICKS_PER_SIGN;
                case SWAP_HOTBAR -> TICKS_PER_HOTBAR_SWAP;
            };
        }
        return ticks;
    }

    /**
     * Ticks as {@code 2h41m}, {@code 41m30s} or {@code 30s} — the form the plan report's headline uses.
     *
     * <p>Integer arithmetic throughout and {@link Locale#ROOT} formatting, because this string lands in a file that
     * two runs of the same build must produce identically, and a locale-dependent or rounding-dependent ETA breaks
     * that comparison for no gain.
     */
    public static String formatEta(long ticks) {
        long seconds = Math.max(0L, ticks) / 20L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh%02dm", hours, minutes);
        }
        if (minutes > 0L) {
            return String.format(Locale.ROOT, "%dm%02ds", minutes, seconds % 60L);
        }
        return seconds + "s";
    }
}
