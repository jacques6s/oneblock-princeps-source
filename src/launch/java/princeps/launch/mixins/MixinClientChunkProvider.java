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

package princeps.launch.mixins;

import princeps.utils.accessor.IChunkArray;
import princeps.utils.accessor.IClientChunkProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.Field;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkProvider implements IClientChunkProvider {

    @Final
    @Shadow
    ClientLevel level;

    /*
     * A ClientChunkCache copy only freezes the cache's reference array; the LevelChunk instances are deliberately
     * shared. Rebuilding that wrapper every Princeps tick used to construct hundreds of empty chunk sections and
     * scan the complete render-distance array, often twice in one builder tick. Keep one snapshot until a chunk
     * event or a view-array/center change invalidates it. Calculations already in flight retain their old wrapper.
     */
    @Unique
    private ClientChunkCache princeps$threadSafeCopy;
    @Unique
    private IChunkArray princeps$sourceArray;
    @Unique
    private int princeps$sourceCenterX;
    @Unique
    private int princeps$sourceCenterZ;
    @Unique
    private int princeps$sourceViewDistance;
    @Unique
    private boolean princeps$snapshotDirty = true;

    @Override
    public synchronized ClientChunkCache createThreadSafeCopy() {
        IChunkArray arr = extractReferenceArray();
        if (!princeps$snapshotDirty
                && princeps$threadSafeCopy != null
                && princeps$sourceArray == arr
                && princeps$sourceCenterX == arr.centerX()
                && princeps$sourceCenterZ == arr.centerZ()
                && princeps$sourceViewDistance == arr.viewDistance()) {
            return princeps$threadSafeCopy;
        }

        ClientChunkCache result = new ClientChunkCache(level, arr.viewDistance() - 3); // -3 because its adds 3 for no reason lmao
        IChunkArray copyArr = ((IClientChunkProvider) result).extractReferenceArray();
        copyArr.copyFrom(arr);
        if (copyArr.viewDistance() != arr.viewDistance()) {
            throw new IllegalStateException(copyArr.viewDistance() + " " + arr.viewDistance());
        }
        princeps$threadSafeCopy = result;
        princeps$sourceArray = arr;
        princeps$sourceCenterX = arr.centerX();
        princeps$sourceCenterZ = arr.centerZ();
        princeps$sourceViewDistance = arr.viewDistance();
        princeps$snapshotDirty = false;
        return princeps$threadSafeCopy;
    }

    @Override
    public synchronized void invalidateThreadSafeCopy() {
        princeps$snapshotDirty = true;
    }

    @Override
    public IChunkArray extractReferenceArray() {
        for (Field f : ClientChunkCache.class.getDeclaredFields()) {
            if (IChunkArray.class.isAssignableFrom(f.getType())) {
                try {
                    return (IChunkArray) f.get(this);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        throw new RuntimeException(Arrays.toString(ClientChunkCache.class.getDeclaredFields()));
    }
}
