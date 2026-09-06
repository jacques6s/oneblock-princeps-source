package princeps.process;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Supplies the vanilla water datapack tag to headless fixtures and restores every original membership. */
final class VanillaWaterTags implements AutoCloseable {
    private final Map<Holder.Reference<Fluid>, List<TagKey<Fluid>>> original = new HashMap<>();
    private final Method bind;

    VanillaWaterTags() throws ReflectiveOperationException {
        bind = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
        bind.setAccessible(true);
        for (Fluid fluid : new Fluid[] {Fluids.WATER, Fluids.FLOWING_WATER}) {
            Holder.Reference<Fluid> holder = (Holder.Reference<Fluid>) BuiltInRegistries.FLUID.wrapAsHolder(fluid);
            List<TagKey<Fluid>> tags = holder.tags().toList();
            original.put(holder, tags);
            List<TagKey<Fluid>> tagged = new ArrayList<>(tags);
            if (!tagged.contains(FluidTags.WATER)) tagged.add(FluidTags.WATER);
            bind.invoke(holder, tagged);
        }
    }

    @Override
    public void close() throws ReflectiveOperationException {
        for (var entry : original.entrySet()) bind.invoke(entry.getKey(), entry.getValue());
    }
}
