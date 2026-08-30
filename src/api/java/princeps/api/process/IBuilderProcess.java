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

package princeps.api.process;

import princeps.api.schematic.ISchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;
import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * @author Brady
 * @since 1/15/2019
 */
public interface IBuilderProcess extends IPrincepsProcess {

    /**
     * Does the template name a real (non-air) block at this WORLD position?
     *
     * <p>Asked by the movement executor before it drops a throwaway block to stand on, because a helper block in a
     * cell the template names is the one kind of scaffolding that cannot be undone: the cell is then occupied by the
     * wrong block AND unbuildable, and clearing it competes with the route that keeps re-placing it.
     *
     * <p>This exists as an interface method rather than a cost, and that distinction is the whole point. The build's
     * {@code costOfPlacingAt} already prices such a cell at infinity, but a cost only governs which path the search
     * CHOOSES — it does not govern what the executor does once a movement is running, and it is not consulted at all
     * by a route computed without the builder's calculation context. Both were observed: a cobblestone landed in
     * {@code 124,-59,114}, a cell wanting {@code sticky_piston}, while the infinite cost was live.
     *
     * <p>Answered from the FULL schematic, never the layer-scoped view. {@code getSchematic} returns null for cells
     * outside the current layer, and "the template is silent here" and "the template has not got to this layer yet"
     * are opposite answers to this question.
     *
     * <p>Default false: with no build running there is no template, and ordinary pathing must be unaffected.
     */
    default boolean templateNamesABlockAt(BlockPos pos) {
        return false;
    }

    /**
     * May movement place the template's own block at this position right now?
     *
     * <p>Most builds have no ordering constraint, hence the permissive default. Row-mode Map Art overrides this so
     * navigation cannot silently build a later cross-slice merely because that pixel happens to be useful as a step.
     */
    default boolean templatePlacementIsLicensedAt(BlockPos placeAt) {
        return true;
    }

    /**
     * May a running movement put a THROWAWAY block into {@code placeAt} to stand on?
     *
     * <p>The owner's rule, in his words: "the only walking movements that happen should be walking movements to a new
     * position from which something is to be built, and the navigation should be precise, exactly to the proven
     * position." A helper block placed to satisfy a goal that was never proven serves no placement — and it is the
     * one kind of mistake that is not reversible by walking away, because it is left standing in the world.
     *
     * <p>Measured, run 50f40a49 on the {@code facings} scenario, the single leftover cobblestone of a 96/96 run: the
     * builder completed {@code 68,-59,67} at tick 964, had no proven stance for anything, and was handed the generic
     * {@code GoalComposite[GoalAdjacent…]} — "get near the remaining work". Two ticks later {@code MovementAscend}
     * built {@code 67,-60,66} to climb one block, because for a mid-height cell in a one-block-thick wall EVERY
     * position that goal accepts is unsupported air. The bot stood on it at tick 980, did nothing there, and at tick
     * 996 walked to the stance that was finally proven — on the other side of the wall, back on the ground — and
     * placed from there at 1039. The block was never used and never removed.
     *
     * <p>Same reason as {@code templateNamesABlockAt} for living on the interface rather than in a cost: a cost
     * governs which path the SEARCH picks, not what a running movement does.
     *
     * <p>Default true: with no build running, nothing about ordinary pathing may change.
     */
    default boolean scaffoldIsLicensedAt(BlockPos placeAt) {
        return true;
    }

    /**
     * Names the server-side area tool used by the current clear session.
     *
     * <p>The name is part of the mechanic on servers whose custom pickaxe is an otherwise ordinary vanilla item.
     * The builder needs the same identity when a damaged 3x3 slice has no centre block: it briefly selects an
     * ordinary pickaxe for the individual leftovers, then must restore the special tool before the next full slice.
     * Implementations without an area-tool route intentionally ignore it.
     */
    default void setAreaToolDisplayName(String displayName) {
    }

    /**
     * Whether the area-tool route is deliberately clearing one damaged slice with an ordinary tool right now.
     *
     * <p>Exposed so the owning client does not fight the builder by restoring the special pickaxe on the same tick.
     */
    /**
     * One line describing what the digger is deciding RIGHT NOW, for the client's trace file.
     *
     * <p>The engine already logs this, but to a logger the shipped client does not surface, so a user reproducing a
     * stall on a real server could hand over a trace full of positions and nothing about the reasoning. Handing the
     * line to the client puts the bot's own account of itself into the file the user can actually send.
     */
    default String areaDigDiagnosis() {
        return "";
    }

    default boolean isAreaBreakCleanupActive() {
        return false;
    }

    /**
     * Does the template want a block here whose ORIENTATION the build depends on?
     *
     * <p>Ein Rechtsklick, der ohne angemeldete Erwartung rausgeht, ist ungeprueft -- die Orientierungs-Sperre in
     * {@code BlockPlaceHelper} haengt an genau dieser Erwartung, und {@code tick()} verbraucht sie am Kopf jedes
     * Ticks ("one forced-input tick owns one immutable expectation"). Bleibt CLICK_RIGHT ueber den Tick hinaus
     * gedrueckt, feuert der naechste einen zweiten Klick mit dem Blick, der gerade zufaellig anliegt.
     *
     * <p>GEMESSEN, basalt-Lauf 20260807-161803: der Client protokollierte 1400 angemeldete Klicks, der Server
     * empfing 1772 verschiedene Pakete -- <b>372, also jeder fuenfte, gingen an der Pruefung vorbei.</b> Einer
     * davon hat den Lauf beendet. Zelle 77,-59,75, gewollt {@code sticky_piston[facing=up]}, zwei Pakete im selben
     * Tick: das erste mit der geprueften Rotation (yaw 123,360 / pitch 41,463, senkrechte Komponente 0,662 gegen
     * 0,626 waagerecht -> Blick nach unten -> Kolben zeigt up, richtig), das zweite mit yaw 81,362 / pitch 27,859
     * (0,874 waagerecht gegen 0,467 senkrecht -> Blick nach west -> Kolben zeigt east). Der zweite landete.
     *
     * <p>Deshalb dieses Praedikat und nicht ein pauschales Verbot: der Wegfinder setzt seine Bruecken- und
     * Saeulenbloecke voellig zu Recht ohne Erwartung (gemessen im selben Lauf: Pitch 88,6 Grad, also senkrecht
     * nach unten -- ein Pillar), und Interaktionsklicks auf Tueren und Repeater sind ABSICHTLICH unbewaffnet.
     * Beides ist harmlos: ein Wegwerfblock hat keine Richtung. Ein ungeprueftes Paket in eine Vorlagezelle, deren
     * Zustand eine Richtung traegt, ist es nicht.
     *
     * <p>Default false: ohne laufenden Bau aendert sich an gewoehnlichem Pathing nichts.
     */
    default boolean orientationIsLoadBearingAt(BlockPos placeAt) {
        return false;
    }

    /**
     * Requests a build for the specified schematic, labeled as specified, with the specified origin.
     *
     * @param name      A user-friendly name for the schematic
     * @param schematic The object representation of the schematic
     * @param origin    The origin position of the schematic being built
     */
    void build(String name, ISchematic schematic, Vec3i origin);

    /**
     * Requests a build whose open cells must advance in a bounded row band.
     *
     * <p>This is an explicit caller choice, never a shape heuristic. A one-layer furniture test and a map picture can
     * have identical dimensions while requiring opposite dependency orders. Implementations that do not support the
     * specialised order keep their ordinary behaviour through this compatibility default.
     *
     * @param name      a user-facing build name
     * @param schematic the schematic to build
     * @param origin    its world origin
     * @param inRows    whether selection must stay inside the Map-Art row band
     */
    default void build(String name, ISchematic schematic, Vec3i origin, boolean inRows) {
        build(name, schematic, origin);
    }

    /**
     * Requests a build for the specified schematic, labeled as specified, with the specified origin.
     *
     * @param name      A user-friendly name for the schematic
     * @param schematic The file path of the schematic
     * @param origin    The origin position of the schematic being built
     * @return Whether or not the schematic was able to load from file
     */
    boolean build(String name, File schematic, Vec3i origin);

    @Deprecated
    default boolean build(String schematicFile, BlockPos origin) {
        File file = new File(new File(Minecraft.getInstance().gameDirectory, "schematics"), schematicFile);
        return build(schematicFile, file, origin);
    }

    void buildOpenSchematic();

    void buildOpenLitematic(int i);

    void pause();

    boolean isPaused();

    void resume();

    void clearArea(BlockPos corner1, BlockPos corner2);

    /**
     * @return A list of block states that are estimated to be placeable by this builder process. You can use this in
     * schematics, for example, to pick a state that the builder process will be happy with, because any variation will
     * cause it to give up. This is updated every tick, but only while the builder process is active.
     */
    List<BlockState> getApproxPlaceable();
    /**
     * Returns the lower bound of the current mining layer if mineInLayers is true.
     * If mineInLayers is false, this will return an empty optional.
     * @return The lower bound of the current mining layer
     */
    Optional<Integer> getMinLayer();

    /**
     * Returns the upper bound of the current mining layer if mineInLayers is true.
     * If mineInLayers is false, this will return an empty optional.
     * @return The upper bound of the current mining layer
     */
    Optional<Integer> getMaxLayer();

    /**
     * What this builder intends to stand at this ABSOLUTE coordinate, or {@code null} if it has no opinion.
     *
     * <p>On the interface because {@code InventoryBehavior} asks it -- from outside the builder -- when the
     * PATHFINDER is about to put a block down, so it can prefer the schematic's own material over a throwaway.
     * That is a shortcut with teeth: the movement placer sets whatever orientation falls out of wherever the bot
     * happens to be looking, so a builder that answers this question is inviting the pathfinder to place its blocks
     * for it, wrongly. An engine that does not want that answers {@code null} for everything, and the selector falls
     * through to {@code acceptableThrowawayItems}.
     *
     * <p>{@code null} is also "outside the schematic" and, following {@code BuilderProcess}, a schematic AIR cell.
     *
     * @return the desired state, or {@code null} -- which is what every engine that does not expose its blueprint to
     * the pathfinder returns
     */
    default BlockState placeAt(int x, int y, int z, BlockState current) {
        return null;
    }

    /**
     * Would this state physically fit at this position -- i.e. would vanilla refuse it for intersecting an entity?
     *
     * <p>On the interface because {@code BackfillProcess} asks it about DIRT, nothing to do with any schematic. It is
     * a pure collision query in every engine; it lives here only because the builder is where the caller already had
     * a reference.
     *
     * @return {@code false} for an engine that cannot answer -- never claim a placement is plausible on no evidence
     */
    default boolean placementPlausible(BlockPos pos, BlockState state) {
        return false;
    }

    /**
     * How many cells the builder has set aside as not currently buildable, so a layer could finish without them.
     *
     * <p>Exposed because retiring a cell IS forward progress -- the work set shrank and the build moved on -- and a
     * watcher that only counts placed blocks reads a run grinding honestly through unbuildable cells as a dead one.
     * A bench did exactly that: it declared STALLED while the builder was steadily deferring and retiring, which
     * ended the run before the retry sweep it was heading for.
     *
     * @return the count for the current build, or 0 for implementations that never retire anything
     */
    default int retiredCellCount() {
        return 0;
    }

    /**
     * How much of the builder's travel was worth taking: {@code {walks started, walks that ended in a placement}}.
     *
     * <p>Exposed because its absence was expensive. Five reasoned attempts at improving which cell the builder walks to
     * were implemented and each measured worse, and none of them could be judged on the way in, because nothing counted
     * whether a walk had paid off. Log-line frequency is a poor stand-in: "1181 stance recoveries" says a great deal of
     * walking happened and nothing about how much of it was useful.
     *
     * @return a two-element array, or {@code {0, 0}} for implementations that do not track it
     */
    default long[] walkEfficiency() {
        return new long[]{0L, 0L};
    }

    /**
     * The two numbers {@link #walkEfficiency()} cannot see: {@code {targets abandoned for want of any stance,
     * retired cells brought back by a neighbour landing}}.
     *
     * <p>The first exists because walk efficiency is counted only after a stance has been chosen, so a cell that is
     * routed to and then abandoned because nothing can be clicked against it costs nothing by that measure -- and on a
     * large schematic that is the dominant expense. A builder judged by walk efficiency alone was being judged by a
     * metric blind to its own biggest cost.
     *
     * <p>The second is the efficacy of un-retiring a cell when a block lands beside it, which is the only route by
     * which a retired cell returns before the retry sweeps.
     *
     * @return a two-element array, or {@code {0, 0}} for implementations that do not track them
     */
    default long[] stanceFailureCounts() {
        return new long[]{0L, 0L};
    }

    /**
     * How well the builder places: {@code {clicks sent, landed correct, landed wrong, blocks broken}}.
     *
     * <p>The first-try rate is landed-correct over clicks-sent, and it is the standard the owner actually holds this
     * builder to: "things should be placed correctly on the first try, it is not that hard". One traced run sent 128
     * placement clicks, saw 86 land, finished 93 cells -- and broke 188 blocks. A builder that breaks twice for every
     * cell it finishes is not building, it is correcting itself, and none of that was visible in any number this
     * bench printed.
     *
     * @return a four-element array, or all zeroes for implementations that do not track it
     */
    default long[] placementQuality() {
        return new long[]{0L, 0L, 0L, 0L};
    }

    /**
     * Whether execution stayed faithful to its plan: {@code {divergences, re-plans}}.
     *
     * <p>A correct final picture is necessary but not sufficient for a first-try builder. A run that recovered
     * through a fresh plan, or merely misclassified its own successful action, must not be graded like one that
     * executed its frozen proof without deviation.
     *
     * @return a two-element array, or {@code {0, 0}} for implementations that do not track it
     */
    default long[] executionQuality() {
        return new long[]{0L, 0L};
    }

    // ==============================================================================================================
    // HOW A BUILD ENDED, and why this is an interface method rather than a log line.
    //
    // Until now a build had exactly two endings and neither could be READ from outside: "Done building" written to
    // the chat, and paused=true for want of materials. Everything else — a cell that cannot be reached, a layer that
    // cannot be finished, a placement that produced the wrong block — ended by simply not producing anything more,
    // and the only observer, the client's schematic module, had to GUESS the reason from the fact that the process
    // had gone inactive. It guesses in ModuleSchematicBuild today, in a branch that writes a fixed sentence.
    //
    // The target specification makes the ending the most important output of the whole builder: a failed placement
    // is a GLOBAL abort with a report, and a layer that still has parked cells at its end is an abort with a report.
    // A report that only exists in a log file is not a report — the owner is standing in the game, not in a text
    // editor. So the ending is a value the process holds, and the client renders it.
    // ==============================================================================================================

    /** How a build finished. Anything other than {@link #COMPLETED} means the schematic is not fully built. */
    enum Ending {
        /** Still running, or no build has been started. */
        RUNNING,
        /** Every cell of every layer is built and verified. The only successful ending. */
        COMPLETED,
        /** A placement failed after it had been simulated, planned and walked to — an assumption was wrong. */
        PLACEMENT_FAILED,
        /** A layer ended with cells still parked: they could not be built and nothing woke them again. */
        LAYER_UNBUILDABLE,
        /** A layer was built but its verification found a cell that is missing, wrong or misoriented. */
        LAYER_VERIFICATION_FAILED,
        /** A material the schematic needs is not obtainable. Not a defect of the algorithm. */
        MATERIALS_MISSING,
        /** The build was cancelled from outside (chat command, module switched off, world change). */
        CANCELLED,
    }

    /**
     * How the current or most recent build ended.
     *
     * <p>Survives {@code onLostControl}, deliberately: the client polls after the process has gone inactive, so an
     * ending cleared during teardown is an ending nobody can read. It is reset when the next build STARTS.
     */
    default Ending ending() {
        return Ending.RUNNING;
    }

    /**
     * The report belonging to {@link #ending()} — human-readable, several lines, empty while running.
     *
     * <p>Names coordinates. A build that stops without saying which cell stopped it is the failure mode this whole
     * channel exists to end: one basalt run lost 245 cells across three layers and 175 of them appeared in no log
     * line at all.
     */
    default java.util.List<String> endingReport() {
        return java.util.Collections.emptyList();
    }
}
