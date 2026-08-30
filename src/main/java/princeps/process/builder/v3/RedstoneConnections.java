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
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

/**
 * The state a redstone wire actually lands in, which is not the item's default and not what it looks like.
 *
 * <p>{@link PlacementOracle#simulate} builds a placed state property by property and deliberately leaves the
 * connective ones alone: {@code north/east/south/west} are in
 * {@link PlacementGeometry#AUTO_RESOLVED_PROP_NAMES}, the game's to resolve, and the acceptance rules skip them. For
 * a block whose OUTLINE follows those properties that is not harmless, because the outline is what the aim point is
 * measured on and what the confirmation ray is cast against.
 *
 * <p>The measured consequence, basalt run {@code 47f7142e}, twice in thirteen minutes and nineteen divergences in
 * total:
 *
 * <pre>
 * v3: click 113,-59,105 against 113,-60,105 face up          &lt;- the plan places a wire
 * v3: DIVERGENCE at action 9 — WRONG_FACE — wanted 113,-59,105 face south
 *     at 113.500,-58.969,105.813
 * </pre>
 *
 * <p>{@code 105.813} is {@code 105 + 13/16}: the south face of the unconnected DOT, whose outline is the single box
 * {@code [3/16,13/16] x [0,1/16] x [3/16,13/16]}. The live wire had a south ARM, and the ray hit its top instead.
 *
 * <p>Nothing exotic put that arm there. {@code RedStoneWireBlock.getStateForPlacement} is
 * {@code getConnectionState(level, this.crossState, pos)} — the CROSS state, all four sides {@code SIDE}, not the
 * default. Follow {@link #connectionState} through for a wire with no redstone neighbours: {@code wasCross} means the
 * dot short circuit does not fire, all four sides come back {@code NONE} from the world, and the completion rules at
 * the bottom then set all four back to {@code SIDE}. <strong>A freshly placed, isolated redstone dust is a
 * cross.</strong> Every wire the planner placed was stored as a dot.
 *
 * <p>This is vanilla's own derivation, transcribed from 26.1.2's bytecode rather than from memory, and
 * {@code RedstoneConnectionsTest} checks the transcription against vanilla's private methods by reflection over
 * every neighbourhood it can build — so "it matches vanilla" is a test result here and not a claim.
 *
 * <h2>What this deliberately does NOT do</h2>
 *
 * <p>It answers "what will a wire placed HERE, NOW, become", which is exactly the question
 * {@code getStateForPlacement} answers and exactly the case that was wrong. It is not a model of
 * {@code updateShape}: a wire already standing when the world was captured carries its true live state already, and a
 * wire the plan places and then places a neighbour of will drift again — vanilla's update keeps the dot/cross
 * distinction sticky, so re-deriving as though it were freshly placed would be a different kind of wrong. That
 * residual is named rather than papered over.
 */
final class RedstoneConnections {

    private RedstoneConnections() {
    }

    /** Vanilla's {@code crossState}: the constructor's registered default with all four sides {@code SIDE}. */
    private static BlockState crossState() {
        return Blocks.REDSTONE_WIRE.defaultBlockState()
                .setValue(RedStoneWireBlock.NORTH, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.SOUTH, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);
    }

    /**
     * The state vanilla's {@code getStateForPlacement} produces for a wire placed at {@code pos} in this world.
     *
     * @return the connected state, or {@code state} unchanged when it is not a redstone wire
     */
    static BlockState stateForPlacement(BlockGetter world, BlockPos pos, BlockState state) {
        if (world == null || pos == null || state == null || !(state.getBlock() instanceof RedStoneWireBlock)) {
            return state;
        }
        try {
            return connectionState(world, crossState().setValue(RedStoneWireBlock.POWER,
                    state.getValue(RedStoneWireBlock.POWER)), pos);
        } catch (RuntimeException ignored) {
            // A world that cannot answer a neighbour read is not a reason to lose the placement; the unchanged state
            // is what this method used to return for every wire anyway.
            return state;
        }
    }

    /** {@code RedStoneWireBlock.getConnectionState}. */
    private static BlockState connectionState(BlockGetter world, BlockState state, BlockPos pos) {
        boolean wasDot = isDot(state);
        BlockState derived = missingConnections(world, Blocks.REDSTONE_WIRE.defaultBlockState()
                .setValue(RedStoneWireBlock.POWER, state.getValue(RedStoneWireBlock.POWER)), pos);
        if (wasDot && isDot(derived)) {
            return derived;
        }
        boolean north = derived.getValue(RedStoneWireBlock.NORTH).isConnected();
        boolean south = derived.getValue(RedStoneWireBlock.SOUTH).isConnected();
        boolean east = derived.getValue(RedStoneWireBlock.EAST).isConnected();
        boolean west = derived.getValue(RedStoneWireBlock.WEST).isConnected();
        // "No connection on this axis" — so a wire connected on neither Z side grows both X arms, and vice versa.
        // This is the rule that makes an isolated dust a cross.
        boolean freeOnZ = !north && !south;
        boolean freeOnX = !east && !west;
        if (!west && freeOnZ) {
            derived = derived.setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);
        }
        if (!east && freeOnZ) {
            derived = derived.setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE);
        }
        if (!north && freeOnX) {
            derived = derived.setValue(RedStoneWireBlock.NORTH, RedstoneSide.SIDE);
        }
        if (!south && freeOnX) {
            derived = derived.setValue(RedStoneWireBlock.SOUTH, RedstoneSide.SIDE);
        }
        return derived;
    }

    /** {@code RedStoneWireBlock.getMissingConnections}. */
    private static BlockState missingConnections(BlockGetter world, BlockState state, BlockPos pos) {
        // Vanilla's own argument pair, kept as it stands: the STATE read is the cell above, and the position handed
        // to isRedstoneConductor is the wire's own. Changing that to the obvious pos.above() would be a different
        // predicate from the one the game evaluates.
        boolean canGoUp = !world.getBlockState(pos.above()).isRedstoneConductor(world, pos);
        BlockState derived = state;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!derived.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction)).isConnected()) {
                derived = derived.setValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction),
                        connectingSide(world, pos, direction, canGoUp));
            }
        }
        return derived;
    }

    /** {@code RedStoneWireBlock.getConnectingSide}, the four-argument form. */
    private static RedstoneSide connectingSide(BlockGetter world, BlockPos pos, Direction direction,
                                               boolean canGoUp) {
        BlockPos side = pos.relative(direction);
        BlockState sideState = world.getBlockState(side);
        if (canGoUp) {
            boolean climbable = sideState.getBlock() instanceof TrapDoorBlock
                    || canSurviveOn(world, side, sideState);
            if (climbable && shouldConnectTo(world.getBlockState(side.above()), null)) {
                return sideState.isFaceSturdy(world, side, direction.getOpposite())
                        ? RedstoneSide.UP : RedstoneSide.SIDE;
            }
        }
        if (shouldConnectTo(sideState, direction)) {
            return RedstoneSide.SIDE;
        }
        if (sideState.isRedstoneConductor(world, side)) {
            return RedstoneSide.NONE;
        }
        return shouldConnectTo(world.getBlockState(side.below()), null)
                ? RedstoneSide.SIDE : RedstoneSide.NONE;
    }

    /** {@code RedStoneWireBlock.shouldConnectTo}. Reimplemented because vanilla's is {@code protected static} and
     *  this class is neither in that package nor a subclass. */
    private static boolean shouldConnectTo(BlockState state, Direction direction) {
        if (state.is(Blocks.REDSTONE_WIRE)) {
            return true;
        }
        if (state.is(Blocks.REPEATER)) {
            Direction facing = state.getValue(RepeaterBlock.FACING);
            return facing == direction || facing.getOpposite() == direction;
        }
        if (state.is(Blocks.OBSERVER)) {
            return direction == state.getValue(ObserverBlock.FACING);
        }
        return state.isSignalSource() && direction != null;
    }

    /** {@code RedStoneWireBlock.canSurviveOn}. */
    private static boolean canSurviveOn(BlockGetter world, BlockPos pos, BlockState state) {
        return state.isFaceSturdy(world, pos, Direction.UP) || state.is(Blocks.HOPPER);
    }

    /** {@code RedStoneWireBlock.isDot}. */
    private static boolean isDot(BlockState state) {
        return !state.getValue(RedStoneWireBlock.NORTH).isConnected()
                && !state.getValue(RedStoneWireBlock.SOUTH).isConnected()
                && !state.getValue(RedStoneWireBlock.EAST).isConnected()
                && !state.getValue(RedStoneWireBlock.WEST).isConnected();
    }
}
