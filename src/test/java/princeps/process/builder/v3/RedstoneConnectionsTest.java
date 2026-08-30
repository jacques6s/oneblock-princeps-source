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
 */

package princeps.process.builder.v3;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The transcription in {@link RedstoneConnections} against vanilla's own derivation, block for block.
 *
 * <p>{@code RedStoneWireBlock.getConnectionState} is private, so production cannot call it — a reflective handle in
 * the builder would be one obfuscation mapping away from failing silently at run time, which is the worst possible
 * failure for a geometry input. So the rule is transcribed, and the transcription is checked HERE, where the
 * deobfuscated jar is on the classpath and reflection costs nothing but a test.
 *
 * <p>That makes "this matches vanilla" a measurement rather than a claim, which matters more than usual: the whole
 * defect this class exists for was an outline nobody had checked against the game.
 */
public class RedstoneConnectionsTest {

    private static final BlockPos WIRE = new BlockPos(8, 8, 8);

    /** Vanilla's private {@code getConnectionState(BlockGetter, BlockState, BlockPos)}. */
    private static Method vanillaConnectionState;

    private static BlockState crossState;

    @BeforeClass
    public static void bootstrapMinecraft() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.wrapAsHolder(item) instanceof Holder.Reference<Item> holder
                    && !holder.areComponentsBound()) {
                holder.bindComponents(DataComponentMap.EMPTY);
            }
        }
        vanillaConnectionState = RedStoneWireBlock.class.getDeclaredMethod("getConnectionState",
                BlockGetter.class, BlockState.class, BlockPos.class);
        vanillaConnectionState.setAccessible(true);
        crossState = Blocks.REDSTONE_WIRE.defaultBlockState()
                .setValue(RedStoneWireBlock.NORTH, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.SOUTH, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);
    }

    /**
     * The one that ended nineteen actions of basalt run {@code 47f7142e}: a wire with nothing around it.
     *
     * <p>Asserted on its own as well as inside the sweep below, because it is the case the whole repair is about and
     * it should fail by name rather than as "neighbourhood 137 of 4096".
     */
    @Test
    public void anIsolatedWireLandsAsACrossAndNotAsTheItemDefault() {
        BlockState placed = RedstoneConnections.stateForPlacement(
                worldOf(Map.of()), WIRE, Blocks.REDSTONE_WIRE.defaultBlockState());

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            assertSame("an isolated dust is a CROSS in vanilla, whatever the item default says: " + direction,
                    RedstoneSide.SIDE,
                    placed.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction)));
        }
        assertEquals("and the outline that follows from it is three boxes, not the dot's one",
                3, OutlineGeometry.localParts(worldOf(Map.of(WIRE, placed)), WIRE).size());
    }

    /**
     * Every neighbourhood this fixture can build, against vanilla's own method.
     *
     * <p>Four horizontal neighbours drawn from a set chosen to exercise each branch of {@code getConnectingSide}:
     * air (nothing), another wire (a plain SIDE), stone (a redstone conductor, which BLOCKS a connection), a slab
     * (sturdy on top, so the ray up the side can connect), a repeater facing each way (connects only along its own
     * axis) and a lever (a signal source). Crossed with what stands above the wire, which is the {@code canGoUp}
     * switch, and with a wire above each neighbour, which is the UP/SIDE decision.
     */
    @Test
    public void theTranscriptionAgreesWithVanillaOnEveryNeighbourhoodThisCanBuild() throws Exception {
        List<BlockState> palette = List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.REDSTONE_WIRE.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.SMOOTH_STONE_SLAB.defaultBlockState(),
                Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.NORTH),
                Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.EAST),
                Blocks.LEVER.defaultBlockState(),
                Blocks.HOPPER.defaultBlockState());
        List<BlockState> above = List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        List<BlockState> aboveNeighbour = List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.REDSTONE_WIRE.defaultBlockState());
        List<BlockState> belowNeighbour = List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.REDSTONE_WIRE.defaultBlockState());

        int checked = 0;
        for (BlockState north : palette) {
            for (BlockState east : palette) {
                for (BlockState over : above) {
                    for (BlockState overNorth : aboveNeighbour) {
                        for (BlockState underNorth : belowNeighbour) {
                            Map<BlockPos, BlockState> cells = new HashMap<>();
                            cells.put(WIRE.north(), north);
                            cells.put(WIRE.east(), east);
                            cells.put(WIRE.above(), over);
                            cells.put(WIRE.north().above(), overNorth);
                            cells.put(WIRE.north().below(), underNorth);
                            cells.put(WIRE.below(), Blocks.STONE.defaultBlockState());
                            PredictedWorld world = worldOf(cells);

                            BlockState mine = RedstoneConnections.stateForPlacement(world, WIRE,
                                    Blocks.REDSTONE_WIRE.defaultBlockState());
                            BlockState theirs = (BlockState) vanillaConnectionState.invoke(
                                    Blocks.REDSTONE_WIRE, world, crossState, WIRE);

                            assertEquals(describe(cells), theirs, mine);
                            checked++;
                        }
                    }
                }
            }
        }
        assertTrue("the sweep has to actually run, or this test asserts nothing", checked >= 500);
    }

    private static String describe(Map<BlockPos, BlockState> cells) {
        List<String> parts = new ArrayList<>();
        cells.forEach((pos, state) -> parts.add(pos.subtract(WIRE) + "=" + state.getBlock()));
        parts.sort(String::compareTo);
        return String.join(" ", parts);
    }

    /** The named cells, stone below the wire, air everywhere else. */
    private static PredictedWorld worldOf(Map<BlockPos, BlockState> cells) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        return PredictedWorld.capture(
                (x, y, z) -> {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState named = cells.get(pos);
                    if (named != null) {
                        return named;
                    }
                    return pos.equals(WIRE.below()) ? stone : air;
                },
                (x, z) -> true, new Vec3i(2, 2, 2), new Vec3i(14, 14, 14), 0, V3Settings.defaults());
    }
}
