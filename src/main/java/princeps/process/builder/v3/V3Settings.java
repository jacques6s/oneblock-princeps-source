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

import princeps.Princeps;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import princeps.pathing.movement.MovementHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable snapshot of every {@code Settings} field the V3 core reads, taken once per build.
 *
 * <p>This record exists for one reason and it is not tidiness: {@code Princeps.settings()} cannot be called from a
 * unit test. {@code PrincepsAPI}'s static initialiser reaches {@code Minecraft.getInstance().gameDirectory}, NPEs,
 * and the catch block then calls {@code Helper.logDirect} which NPEs again — so the NPE escapes the static
 * initialiser as an {@code ExceptionInInitializerError} that no {@code Bootstrap.bootStrap()} can prevent. Any class
 * that touches settings is therefore permanently untestable, which is why the planner, the oracle and the geometry
 * take a snapshot as a parameter instead. {@link #capture()} is the one and only settings read in the whole package
 * and it is called from {@link PlannedBuilderProcess}, never from a test.
 *
 * <p>Held for the duration of a build rather than re-read per tick, for the same reason {@code CalculationContext}
 * reads each setting exactly once: a plan proven under one set of acceptance rules and executed under another is not
 * a proof of anything.
 *
 * <p>The two predicates below are the only two of V2's 24 acceptance statics that are not referentially transparent —
 * everything else depends solely on its arguments, these two depend on user configuration. Given a snapshot they
 * become pure again, which is why they take one. They are exposed here as instance methods because that is how the
 * oracle asks them, but the bodies live in {@link PlacementGeometry} alongside the other 22 and these are one-line
 * delegates. Two independently maintained copies of {@code valid} is exactly the bug that ends with a cell the
 * planner proved and the executor rejects forever.
 *
 * @param buildIgnoreDirection  bypass {@link PlacementGeometry#ORIENTATION_PROPS} when comparing states
 * @param buildIgnoreProperties property names a mismatch on which is accepted anyway
 * @param okIfWater             accept a liquid where the schematic wants something else
 * @param okIfAir               blocks the schematic may ask for that air satisfies
 * @param buildIgnoreBlocks     existing blocks that count as "already air" where the schematic wants air
 * @param buildIgnoreExisting   accept any non-air block as satisfying any cell (disabled under {@code itemVerify})
 * @param buildValidSubstitutes desired block to the blocks that may stand in for it (disabled under {@code itemVerify})
 */
public record V3Settings(
        boolean buildIgnoreDirection,
        List<String> buildIgnoreProperties,
        boolean okIfWater,
        List<Block> okIfAir,
        List<Block> buildIgnoreBlocks,
        boolean buildIgnoreExisting,
        Map<Block, List<Block>> buildValidSubstitutes,
        List<Block> blocksToAvoid,
        boolean allowVines,
        boolean allowWalkOnBottomSlab,
        boolean allowWalkOnMagmaBlocks,
        boolean assumeWalkOnLava,
        /** When false the planner captures every cell DRY and the report declares the count. See
         *  {@code Settings.builderV3Waterlogging}: this is a scope decision, not a limitation. */
        boolean waterlogging
) implements MovementHelper.WalkSettings {

    /** Defensive deep copy: the live {@code Setting.value} lists stay mutable and a user {@code #set} mid-build must
     *  not retroactively change what the frozen plan was proven against. */
    public V3Settings {
        buildIgnoreProperties = List.copyOf(buildIgnoreProperties);
        okIfAir = List.copyOf(okIfAir);
        buildIgnoreBlocks = List.copyOf(buildIgnoreBlocks);
        blocksToAvoid = List.copyOf(blocksToAvoid);
        Map<Block, List<Block>> substitutes = new HashMap<>();
        buildValidSubstitutes.forEach((block, alternatives) -> substitutes.put(block, List.copyOf(alternatives)));
        buildValidSubstitutes = Map.copyOf(substitutes);
    }

    /** Source-compatible constructor for callers concerned only with builder acceptance settings. */
    public V3Settings(boolean buildIgnoreDirection, List<String> buildIgnoreProperties, boolean okIfWater,
                      List<Block> okIfAir, List<Block> buildIgnoreBlocks, boolean buildIgnoreExisting,
                      Map<Block, List<Block>> buildValidSubstitutes) {
        this(buildIgnoreDirection, buildIgnoreProperties, okIfWater, okIfAir, buildIgnoreBlocks,
                buildIgnoreExisting, buildValidSubstitutes, List.of(Blocks.TRIPWIRE), false, true, false, false,
                false);
    }

    /**
     * Read the live settings. THE ONLY {@code Princeps.settings()} call in {@code princeps.process.builder.v3} — see
     * the class javadoc for why. Main thread, from the process, at build start.
     */
    public static V3Settings capture() {
        return new V3Settings(
                Princeps.settings().buildIgnoreDirection.value,
                Princeps.settings().buildIgnoreProperties.value,
                Princeps.settings().okIfWater.value,
                Princeps.settings().okIfAir.value,
                Princeps.settings().buildIgnoreBlocks.value,
                Princeps.settings().buildIgnoreExisting.value,
                Princeps.settings().buildValidSubstitutes.value,
                Princeps.settings().blocksToAvoid.value,
                Princeps.settings().allowVines.value,
                Princeps.settings().allowWalkOnBottomSlab.value,
                Princeps.settings().allowWalkOnMagmaBlocks.value,
                Princeps.settings().assumeWalkOnLava.value,
                Princeps.settings().builderV3Waterlogging.value
        );
    }

    /** The stock configuration, for tests and for any code path that must not depend on what the user has set. */
    public static V3Settings defaults() {
        return new V3Settings(false, new ArrayList<>(), false, new ArrayList<>(), new ArrayList<>(), false,
                new HashMap<>(), List.of(Blocks.TRIPWIRE), false, true, false, false,
                // Waterlogging out of scope by default, matching Settings.builderV3Waterlogging. A test that wants it
                // has to ask, so a headless fixture cannot quietly plan a bucket pass the live default never runs.
                false);
    }

    /** {@link MovementHelper.WalkSettings}: membership against the frozen avoidance list. */
    @Override
    public boolean avoids(Block block) {
        return this.blocksToAvoid.contains(block);
    }

    /**
     * Are these two states the same as far as the builder is concerned? Port of {@code BuilderProcess.sameBlockstate}
     * (:5123) with the two settings reads lifted into this snapshot.
     *
     * <p>Iterates {@code first}'s properties, not {@code second}'s — deliberately asymmetric, and
     * {@link PlacementGeometry#matchesExceptInteraction} iterates the other one. Do not unify them.
     */
    public boolean sameBlockstate(BlockState first, BlockState second) {
        return PlacementGeometry.sameBlockstate(this, first, second);
    }

    /**
     * Does {@code current} satisfy {@code desired}? Port of {@code BuilderProcess.valid} (:5244) with the five
     * settings reads lifted into this snapshot.
     *
     * <p>{@code itemVerify} is not a convenience flag: passing {@code true} disables the {@code buildIgnoreExisting}
     * and {@code buildValidSubstitutes} escapes, so "would this click be accepted" and "is this cell done" genuinely
     * ask different questions of the same landed state. V2 uses {@code true} for the former and {@code false} for the
     * latter on purpose.
     *
     * @param desired null means "outside the schematic", which is always satisfied
     */
    public boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        return PlacementGeometry.valid(this, current, desired, itemVerify);
    }
}
