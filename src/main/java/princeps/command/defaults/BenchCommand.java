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

package princeps.command.defaults;

import princeps.api.IPrinceps;
import princeps.api.command.Command;
import princeps.api.command.argument.IArgConsumer;
import princeps.api.command.exception.CommandException;
import princeps.api.command.exception.CommandInvalidStateException;
import princeps.api.utils.BetterBlockPos;
import princeps.process.builder.bench.BenchSchematics;
import princeps.process.builder.bench.BuilderBench;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs an automated builder benchmark scenario. The scenario geometry is defined in code (no schematic file), the
 * real builder runs against the real world, and the bench verifies the result independently, logging everything under
 * a {@code [BENCH]} marker so a run can be read back from {@code latest.log} without watching the game.
 */
public class BenchCommand extends Command {

    private final BuilderBench bench;

    public BenchCommand(IPrinceps princeps, BuilderBench bench) {
        super(princeps, "bench");
        this.bench = bench;
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (args.hasAny() && args.peekString().equalsIgnoreCase("stop")) {
            args.get();
            bench.stop("ABORTED");
            logDirect("Bench aborted");
            return;
        }
        args.requireMin(1);
        String name = args.getString();
        BenchSchematics.Scenario scenario = BenchSchematics.byName(name);
        if (scenario == null) {
            throw new CommandInvalidStateException("Unknown scenario '" + name
                    + "'. Available: " + BenchSchematics.scenarioNames());
        }
        // Build a few blocks in front of the player so the bot has to walk to it rather than starting inside it.
        BetterBlockPos feet = ctx.playerFeet();
        BetterBlockPos origin = new BetterBlockPos(feet.x + 3, feet.y, feet.z + 3);
        // The bench prepares its own area and materials — no operator steps required.
        bench.start(scenario, origin);
        logDirect("Bench started: " + scenario.name + " (" + scenario.cellCount()
                + " cells). Watch the log for [BENCH] lines.");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return Stream.of("ring7", "ringbig", "ringresume", "wall", "floor", "dig", "digbig", "oriented", "stop");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Run an automated builder benchmark";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Runs a synthetic builder scenario and verifies the result independently.",
                "",
                "Scenarios: " + BenchSchematics.scenarioNames(),
                "  ring7    - one-wide 7x7 ring, 2 high (the live self-box geometry)",
                "  wall     - straight 11x2 wall",
                "  floor    - 9x9 floor slab (throughput baseline)",
                "  oriented - slabs / stairs / door (first-try orientation)",
                "",
                "Usage:",
                "> bench <scenario> - start a scenario at +3/+3 from the player",
                "> bench stop       - abort the running bench"
        );
    }
}
