/*
 * This file is part of the Princeps project.
 */
package princeps.process;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.BeforeClass;
import org.junit.Test;
import princeps.api.utils.BetterBlockPos;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderTrimDependencyPolicyTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void trimRetainsTheMissingProviderOfANearbyAttachment() {
        Map<String, String> dependency = Collections.singletonMap("north-lever", "lamp");

        Set<String> retained = BuilderProcess.dependencyClosedSelection(
                Collections.singleton("north-lever"), dependency::get);

        assertEquals(new HashSet<>(Arrays.asList("north-lever", "lamp")), retained);
        assertFalse(retained.contains("unrelated-lever"));
    }

    @Test
    public void northWallLeverDependsOnTheLampImmediatelySouthOfIt() {
        BlockState northWallLever = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);

        BetterBlockPos support = BuilderProcess.requiredSupportPosition(
                new BetterBlockPos(98, -59, 97), northWallLever);

        assertEquals(new BetterBlockPos(98, -59, 98), support);
    }

    @Test
    public void trimClosesShortDependencyChainsToAFixedPoint() {
        Map<String, String> dependency = new HashMap<>();
        dependency.put("attachment", "provider");
        dependency.put("provider", "foundation");

        Set<String> retained = BuilderProcess.dependencyClosedSelection(
                Collections.singleton("attachment"), dependency::get);

        assertEquals(new HashSet<>(Arrays.asList("attachment", "provider", "foundation")), retained);
    }

    @Test
    public void watchdogRejectsUnsupportedPlacementCellsAsUnroutable() {
        assertFalse(BuilderProcess.placementCellIsRoutable(false, true, false));
        assertTrue(BuilderProcess.placementCellIsRoutable(false, true, true));
        assertFalse(BuilderProcess.placementCellIsRoutable(true, true, true));
        assertFalse(BuilderProcess.placementCellIsRoutable(false, false, true));
    }
}
