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

package princeps;

import princeps.api.PrincepsAPI;
import princeps.api.IPrinceps;
import princeps.api.Settings;
import princeps.api.behavior.IBehavior;
import princeps.api.event.listener.IEventBus;
import princeps.api.process.IBuilderProcess;
import princeps.api.process.IPrincepsProcess;
import princeps.api.process.IElytraProcess;
import princeps.api.utils.IPlayerContext;
import princeps.behavior.*;
import princeps.cache.WorldProvider;
import princeps.command.manager.CommandManager;
import princeps.event.GameEventHandler;
import princeps.process.*;
import princeps.selection.SelectionManager;
import princeps.utils.BlockStateInterface;
import princeps.utils.GuiClick;
import princeps.utils.InputOverrideHandler;
import princeps.utils.PathingControlManager;
import princeps.utils.player.PrincepsPlayerContext;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author Brady
 * @since 7/31/2018
 */
public class Princeps implements IPrinceps {

    private static final ThreadPoolExecutor threadPool;

    static {
        threadPool = new ThreadPoolExecutor(4, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
    }

    private final Minecraft mc;
    private final Path directory;

    private final GameEventHandler gameEventHandler;

    private final PathingBehavior pathingBehavior;
    private final LookBehavior lookBehavior;
    private final InventoryBehavior inventoryBehavior;
    private final SurvivalBehavior survivalBehavior;
    /** Automated builder test bench (dev/diagnostic tooling; inert unless a bench scenario is started). */
    private final princeps.process.builder.bench.BuilderBench builderBench;
    private final InputOverrideHandler inputOverrideHandler;

    private final FollowProcess followProcess;
    private final MineProcess mineProcess;
    private final GetToBlockProcess getToBlockProcess;
    private final CustomGoalProcess customGoalProcess;
    /** The original one-cell-at-a-time builder. Registered every session; active only if it is the selected engine. */
    private final BuilderProcess builderProcessV2;
    /** Builder V3 -- plans and proves the whole build before the first block. Same deal: registered, maybe inactive. */
    private final princeps.process.builder.v3.PlannedBuilderProcess builderProcessV3;
    /** Whichever of the two the session selected. Resolved once, lazily; see {@link #getBuilderProcess()}. */
    private volatile IBuilderProcess builderProcess;
    private final ExploreProcess exploreProcess;
    private final FarmProcess farmProcess;
    private final InventoryPauserProcess inventoryPauserProcess;
    private final IElytraProcess elytraProcess;

    private final PathingControlManager pathingControlManager;
    private final SelectionManager selectionManager;
    private final CommandManager commandManager;

    private final IPlayerContext playerContext;
    private final WorldProvider worldProvider;

    public BlockStateInterface bsi;

    Princeps(Minecraft mc) {
        this.mc = mc;
        this.gameEventHandler = new GameEventHandler(this);

        this.directory = mc.gameDirectory.toPath().resolve("princeps");
        if (!Files.exists(this.directory)) {
            try {
                Files.createDirectories(this.directory);
            } catch (IOException ignored) {}
        }

        // Define this before behaviors try and get it, or else it will be null and the builds will fail!
        this.playerContext = new PrincepsPlayerContext(this, mc);

        {
            this.lookBehavior         = this.registerBehavior(LookBehavior::new);
            this.pathingBehavior      = this.registerBehavior(PathingBehavior::new);
            this.inventoryBehavior    = this.registerBehavior(InventoryBehavior::new);
            this.inputOverrideHandler = this.registerBehavior(InputOverrideHandler::new);
            this.registerBehavior(WaypointBehavior::new);
            this.survivalBehavior     = this.registerBehavior(SurvivalBehavior::new);
            this.builderBench         = this.registerBehavior(princeps.process.builder.bench.BuilderBench::new);
            this.registerBehavior(princeps.process.builder.bench.MapArtBenchDriver::new);
            this.registerBehavior(princeps.process.builder.bench.AreaPickBenchDriver::new);
        }

        this.pathingControlManager = new PathingControlManager(this);
        {
            this.followProcess           = this.registerProcess(FollowProcess::new);
            this.mineProcess             = this.registerProcess(MineProcess::new);
            this.customGoalProcess       = this.registerProcess(CustomGoalProcess::new); // very high iq
            this.getToBlockProcess       = this.registerProcess(GetToBlockProcess::new);
            // Both builder engines are registered. Exactly one can ever be active: each reports isActive() false
            // unless it is the one getBuilderProcess() hands out, and every build entry point in the codebase goes
            // through that getter, so the unselected engine is never given a schematic in the first place.
            this.builderProcessV2        = this.registerProcess(BuilderProcess::new);
            this.builderProcessV3        = this.registerProcess(princeps.process.builder.v3.PlannedBuilderProcess::new);
            this.exploreProcess          = this.registerProcess(ExploreProcess::new);
            this.farmProcess             = this.registerProcess(FarmProcess::new);
            this.inventoryPauserProcess  = this.registerProcess(InventoryPauserProcess::new);
            this.elytraProcess           = this.registerProcess(ElytraProcess::create);
            this.registerProcess(BackfillProcess::new);
        }

        this.worldProvider = new WorldProvider(this);
        this.selectionManager = new SelectionManager(this);
        this.commandManager = new CommandManager(this);
    }

    public void registerBehavior(IBehavior behavior) {
        this.gameEventHandler.registerEventListener(behavior);
    }

    public <T extends IBehavior> T registerBehavior(Function<Princeps, T> constructor) {
        final T behavior = constructor.apply(this);
        this.registerBehavior(behavior);
        return behavior;
    }

    public <T extends IPrincepsProcess> T registerProcess(Function<Princeps, T> constructor) {
        final T behavior = constructor.apply(this);
        this.pathingControlManager.registerProcess(behavior);
        return behavior;
    }

    @Override
    public PathingControlManager getPathingControlManager() {
        return this.pathingControlManager;
    }

    @Override
    public InputOverrideHandler getInputOverrideHandler() {
        return this.inputOverrideHandler;
    }

    @Override
    public CustomGoalProcess getCustomGoalProcess() {
        return this.customGoalProcess;
    }

    @Override
    public GetToBlockProcess getGetToBlockProcess() {
        return this.getToBlockProcess;
    }

    @Override
    public IPlayerContext getPlayerContext() {
        return this.playerContext;
    }

    @Override
    public FollowProcess getFollowProcess() {
        return this.followProcess;
    }

    /**
     * The builder engine this session runs, resolved on the first ask and never again.
     *
     * <p>Resolved lazily rather than in the constructor because {@code Princeps} is constructed from inside
     * {@code PrincepsAPI}'s static initialiser, and reading a {@code Setting} from there depends on the assignment
     * order of two static fields in another class. The first ask happens on the first tick that touches the builder,
     * which is session start for every practical purpose, and pinning it once means a setting flipped mid-build
     * cannot swap engines out from under a frozen plan.
     */
    @Override
    public IBuilderProcess getBuilderProcess() {
        IBuilderProcess selected = this.builderProcess;
        return selected != null ? selected : this.resolveBuilderEngine();
    }

    private synchronized IBuilderProcess resolveBuilderEngine() {
        if (this.builderProcess != null) {
            return this.builderProcess;
        }
        // The system property wins over the setting: settings are not auto-saved, so a bench that wrote one would
        // either lose it or leak it into the next launch. -Dprinceps.builder.engine=v3 is per-run and leaves no trace.
        String override = System.getProperty("princeps.builder.engine", "").trim();
        boolean v3 = override.isEmpty() ? settings().builderEngineV3.value : override.equalsIgnoreCase("v3");
        IBuilderProcess engine = v3 ? this.builderProcessV3 : this.builderProcessV2;
        // Do NOT memoize a null. The two engine fields are assigned one after the other in the constructor, and
        // registerProcess calls onLostControl on each process as it is built -- so there is a window in which
        // builderProcessV3 is still null and a callback could land here. Nothing reaches it today (V3's isActive()
        // tests schematic != null BEFORE selected(), so construction never asks), but memoizing the null would pin
        // this session to a NullPointerException on every later ask, with the cause thirty frames upstream. Returning
        // unresolved instead means the next ask -- after the constructor has finished -- resolves correctly.
        if (engine == null) {
            return this.builderProcessV2;
        }
        this.builderProcess = engine;
        princeps.api.utils.Helper.HELPER.logMechanic("builder engine = " + (v3 ? "v3" : "v2")
                + " (" + engine.getClass().getSimpleName() + "), from "
                + (override.isEmpty() ? "settings.builderEngineV3" : "-Dprinceps.builder.engine=" + override));
        return engine;
    }

    public InventoryBehavior getInventoryBehavior() {
        return this.inventoryBehavior;
    }

    public princeps.process.builder.bench.BuilderBench getBuilderBench() {
        return this.builderBench;
    }

    public SurvivalBehavior getSurvivalBehavior() {
        return this.survivalBehavior;
    }

    @Override
    public LookBehavior getLookBehavior() {
        return this.lookBehavior;
    }

    @Override
    public ExploreProcess getExploreProcess() {
        return this.exploreProcess;
    }

    @Override
    public MineProcess getMineProcess() {
        return this.mineProcess;
    }

    @Override
    public FarmProcess getFarmProcess() {
        return this.farmProcess;
    }

    public InventoryPauserProcess getInventoryPauserProcess() {
        return this.inventoryPauserProcess;
    }

    @Override
    public PathingBehavior getPathingBehavior() {
        return this.pathingBehavior;
    }

    @Override
    public SelectionManager getSelectionManager() {
        return selectionManager;
    }

    @Override
    public WorldProvider getWorldProvider() {
        return this.worldProvider;
    }

    @Override
    public IEventBus getGameEventHandler() {
        return this.gameEventHandler;
    }

    @Override
    public CommandManager getCommandManager() {
        return this.commandManager;
    }

    @Override
    public IElytraProcess getElytraProcess() {
        return this.elytraProcess;
    }

    @Override
    public void openClick() {
        new Thread(() -> {
            try {
                Thread.sleep(100);
                mc.execute(() -> mc.setScreen(new GuiClick()));
            } catch (Exception ignored) {}
        }).start();
    }

    public Path getDirectory() {
        return this.directory;
    }

    public static Settings settings() {
        return PrincepsAPI.getSettings();
    }

    public static Executor getExecutor() {
        return threadPool;
    }
}
