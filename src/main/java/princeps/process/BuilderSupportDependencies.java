/* This file is part of Princeps. SPDX-License-Identifier: LGPL-3.0-or-later */
package princeps.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import princeps.api.schematic.ISchematic;
import princeps.utils.BlockStateInterface;

import java.util.List;

/** Retains existing model material and direct Vanilla supports; no physics or drop-recovery simulation. */
final class BuilderSupportDependencies {
    enum Reason { ALLOWED, MODEL_BLOCK, REQUIRED_SUPPORT, UNKNOWN_WORLD }
    record Decision(Reason reason, BlockPos dependent) {
        boolean allowed() { return reason == Reason.ALLOWED; }
    }
    private static final Decision ALLOWED = new Decision(Reason.ALLOWED, null);
    private static final Direction[] NEIGHBOURS = Direction.values();
    private final ISchematic model;
    private final Vec3i origin;
    private final List<BlockState> stock;
    private final View before;

    BuilderSupportDependencies(ISchematic model, Vec3i origin, List<BlockState> stock,
                               BlockStateInterface blocks, LevelReader ambient) {
        this.model = model;
        this.origin = origin;
        this.stock = stock;
        this.before = new View(blocks, ambient, null, null);
    }

    /** Main-thread execution view: loaded live cells, without a path-worker cache or global settings lookup. */
    static BuilderSupportDependencies currentWorld(ISchematic model, Vec3i origin, List<BlockState> stock, LevelReader world) {
        return new BuilderSupportDependencies(model, origin, stock, null, world);
    }

    Decision removal(BlockPos removed) {
        return removal(removed, null);
    }

    /** The caller may supply only this tick's explicitly selected primary repair, never a navigation target. */
    Decision removal(BlockPos removed, BlockState selectedRepairState) {
        // An ordinary builder context always captures a model. Excavation explicitly does not enter this policy.
        if (!inside(removed) && !touchesModel(removed)) return ALLOWED;
        View after = before.withBlock(removed, Blocks.AIR.defaultBlockState());
        try {
            BlockState primary = before.getBlockState(removed); // Unknown terrain is never cheap mining.
            BlockState primaryWanted = inside(removed) ? desired(removed, primary) : null;
            // The working layer can hide an already-built cell. Its full-model material still belongs here:
            // neither a finite path penalty nor a possible drop proves that we can restore it afterwards.
            // Wrong properties retain their material too, except for the existing explicitly selected repair.
            if (primaryWanted != null && !primaryWanted.isAir() && primary.is(primaryWanted.getBlock())
                    && !(primary == selectedRepairState && primary != primaryWanted)) {
                return new Decision(Reason.MODEL_BLOCK, removed.immutable());
            }
            BlockPos repairPartner = primary == selectedRepairState ? pairedRepairPartner(removed, primary) : null;
            for (Direction direction : NEIGHBOURS) {
                BlockPos neighbour = removed.relative(direction);
                if (!inside(neighbour)) continue;
                BlockState current = before.getBlockState(neighbour);
                BlockState wanted = desired(neighbour, current);
                if (neighbour.equals(repairPartner)) continue;
                // The current open/toggle/orientation state still owns its real support. A strict valid() check
                // would accidentally authorize destroying an open door while it waits to be closed again.
                if (wanted != null && !wanted.isAir() && current.is(wanted.getBlock())
                        && current.canSurvive(before, neighbour) && !current.canSurvive(after, neighbour)) {
                    return new Decision(Reason.REQUIRED_SUPPORT, neighbour.immutable());
                }
            }
            return ALLOWED;
        } catch (UnknownWorld ignored) {
            return new Decision(Reason.UNKNOWN_WORLD, null);
        }
    }

    private BlockPos pairedRepairPartner(BlockPos primary, BlockState current) {
        // These Vanilla blocks create/remove their upper half as part of one lower-half operation. This is not
        // an exemption for arbitrary matching model neighbours, outside terrain, or a merely open wooden door.
        if (!inside(primary) || !(current.getBlock() instanceof DoorBlock || current.getBlock() instanceof DoublePlantBlock)
                || current.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) return null;
        BlockPos upper = primary.above();
        if (!inside(upper)) return null;
        BlockState actualUpper = before.getBlockState(upper);
        BlockState wantedLower = desired(primary, current), wantedUpper = desired(upper, actualUpper);
        if (wantedLower == null || wantedLower == current || !wantedLower.is(current.getBlock())
                || wantedLower.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER
                || wantedUpper == null || !wantedUpper.is(current.getBlock())
                || wantedUpper.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.UPPER
                || !actualUpper.is(current.getBlock())
                || actualUpper.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.UPPER) return null;
        return upper;
    }

    /** A diagnostic dependency, never authorization to place outside the model or later remove this support. */
    BlockPos missingExternalSupport(BlockPos target, BlockState wanted, BlockState proposedSupport) {
        if (!inside(target)) return null;
        if (wanted.canSurvive(before, target)) return null;
        for (Direction direction : NEIGHBOURS) {
            BlockPos support = target.relative(direction);
            if (!inside(support) && before.getBlockState(support).isAir()
                    && wanted.canSurvive(before.withBlock(support, proposedSupport), target)) {
                return support.immutable();
            }
        }
        return null;
    }

    private boolean touchesModel(BlockPos p) {
        int x=p.getX()-origin.getX(), y=p.getY()-origin.getY(), z=p.getZ()-origin.getZ();
        if (x < -1 || y < -1 || z < -1 || x > model.widthX() || y > model.heightY() || z > model.lengthZ()) return false;
        for (Direction d : NEIGHBOURS) if (insideLocal(x+d.getStepX(),y+d.getStepY(),z+d.getStepZ())) return true;
        return false;
    }

    private boolean inside(BlockPos p) {
        int x = p.getX() - origin.getX(), y = p.getY() - origin.getY(), z = p.getZ() - origin.getZ();
        return insideLocal(x,y,z);
    }

    private boolean insideLocal(int x,int y,int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < model.widthX() && y < model.heightY() && z < model.lengthZ();
    }

    private BlockState desired(BlockPos p, BlockState current) {
        int x = p.getX() - origin.getX(), y = p.getY() - origin.getY(), z = p.getZ() - origin.getZ();
        return model.inSchematic(x, y, z, current) ? model.desiredState(x, y, z, current, stock) : null;
    }

    /** No Level/ClientLevel subtype, mutation API, global override or shared mutable BSI assumption. */
    static final class View implements LevelReader {
        private final BlockStateInterface blocks;
        private final LevelReader ambient;
        private final BlockPos replaced;
        private final BlockState replacement;

        View(BlockStateInterface blocks, LevelReader ambient, BlockPos replaced, BlockState replacement) {
            this.blocks = blocks;
            this.ambient = ambient;
            this.replaced = replaced == null ? null : replaced.immutable();
            this.replacement = replacement;
        }

        View withBlock(BlockPos pos, BlockState state) { return new View(blocks, ambient, pos, state); }

        @Override public BlockState getBlockState(BlockPos pos) {
            if (pos.getY() < getMinY() || pos.getY() >= getMinY() + getHeight()) return Blocks.AIR.defaultBlockState();
            if (!hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) throw new UnknownWorld();
            return pos.equals(replaced) ? replacement : blocks == null
                    ? ambient.getBlockState(pos) : blocks.get0(pos.getX(), pos.getY(), pos.getZ());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        // Returning a real chunk would bypass the single-block view. Unhandled Vanilla queries are unknown,
        // never permission to mine. The direct block-support predicates use getBlockState/getFluidState.
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) { throw new UnknownWorld(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { throw new UnknownWorld(); }
        @Override public boolean hasChunk(int x, int z) {
            return blocks == null ? ambient.hasChunk(x, z) : blocks.worldContainsLoadedChunk(x << 4, z << 4);
        }
        @Override public BlockGetter getChunkForCollisions(int x, int z) {
            if (!hasChunk(x, z)) throw new UnknownWorld();
            return this;
        }
        @Override public List<VoxelShape> getEntityCollisions(Entity entity, AABB bounds) { throw new UnknownWorld(); }
        @Override public int getMinY() { return ambient.getMinY(); }
        @Override public int getHeight() { return ambient.getHeight(); }
        @Override public int getHeight(Heightmap.Types type, int x, int z) { throw new UnknownWorld(); }
        @Override public LevelLightEngine getLightEngine() { throw new UnknownWorld(); }
        @Override public int getSkyDarken() { return ambient.getSkyDarken(); }
        @Override public BiomeManager getBiomeManager() { throw new UnknownWorld(); }
        @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) { throw new UnknownWorld(); }
        @Override public boolean isClientSide() { return ambient.isClientSide(); }
        @Override public int getSeaLevel() { return ambient.getSeaLevel(); }
        @Override public DimensionType dimensionType() { return ambient.dimensionType(); }
        @Override public WorldBorder getWorldBorder() { return ambient.getWorldBorder(); }
        @Override public RegistryAccess registryAccess() { return ambient.registryAccess(); }
        @Override public FeatureFlagSet enabledFeatures() { return ambient.enabledFeatures(); }
        @Override public EnvironmentAttributeReader environmentAttributes() { return ambient.environmentAttributes(); }
    }

    static final class UnknownWorld extends RuntimeException {
        UnknownWorld() { super("Support dependency cannot be checked in the loaded block view", null, false, false); }
    }
}
