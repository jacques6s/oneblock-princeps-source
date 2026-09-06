# Builder route context preservation

Base: `99732e8cdd8cb74375f57441a3402c6ca10c1954`. This change preserves the calculation context when the builder continues an existing route. It changes no placement, breaking, material, fall-height, or lane permission.

## Reproduction and cause

The independent packaged component run `Princeps-component-bench-68/runs/scaffold-combined-20260906-01` used runtime SHA-256 `b12d8292cbcf09f16e495eb6c0a9f40e1bdc7136ea780cd22f6127c6958491a6`, matching this base. Its final correct target was `81,-56,74` at trace tick3084 (144/272 structural targets). Three dirt helpers remained where the full template requests AIR: `74,-60..-58,71`. The goal became their `GoalBreak` composite, with no elected placement cell.

At ticks3114,3144,3175 and repeatedly thereafter, `RELEASE` reported a failed route and the builder returned `CANCEL_AND_SET_GOAL`. `holdStillWithoutTearingUpTheRoute` converted that idle cancellation to a plain `REVALIDATE_GOAL_AND_PATH` command. `PathingBehavior.secretInternalSetGoalAndPath` assigns a fresh **generic** `CalculationContext` for every plain command. Consequently the continuation silently lost `BuilderCalculationContext`, including its template-sensitive break costs and lane-specific helper restrictions. The next ordinary builder command restored them.

The trace follows that cycle: after tick3175's release, ticks3176/3177 have `len4/0:MovementDescend`; tick3178 again has no path and no displacement. Ordinary searches repeatedly exhaust25 nodes, whereas the intervening search reports a reached goal after28 nodes. The exact proposed descend endpoint and executor cancellation branch are not recorded, so the inference that this short path would cut a correct platform block is not presented as a measured block break. The context loss itself is reproduced by executing the real command handoff in the regression test. No later world progress was confirmed; the engine correctly stopped after60 active seconds at tick4376.

## Scope

Three continuations now retain the currently attached calculation context in `PathingCommandContext`: the progress diagnosis, an idle hold that keeps its route, and a committed placement's airborne/non-cancellable hold. Their command type and goal are unchanged. An absent context retains the previous initialization behavior.

The complete plain-command inventory was reviewed. Null cancellation/pause commands and genuine new requests are unchanged. In particular, AutoDig's initial travel to its corridor intentionally starts a generic navigation request; a later continuation also preserves that generic context. `PathingBehavior` itself is unchanged, so a new generic process does not inherit restrictions from an old builder.

## Verification

Before the production edit, `BuilderRouteContextTest` executed the real idle-hold method and handed its result to the real `PathingBehavior.secretInternalSetGoalAndPath`: six tests ran, the two laneA/laneB continuation cases failed at the generic-context sentinel, and four countercases passed. Evidence: `build/route-context-evidence/red.log` and `red.xml`. An earlier fixture bootstrap failure is retained separately and is not the RED reproduction.

After the edit, all10 targeted cases passed (`green.log`, `green.xml`). They verify exact context identity, laneA refusing helpers, laneB accepting template AIR while refusing a planned solid cell, retained break prohibition, real click/explicit pause/progress-stop/completion cancellation, an explicit new context replacing the previous one, an existing generic approach staying generic, and missing-context initialization. Bytecode checks ensure all three continuation sites call this tested command helper.

Tests allocate per-test player-independent engine objects, following the existing headless fixtures. The goal is already at the synthetic feet, so the real pathing handoff returns before starting A*. The new-generic-command countercase enters the real generic constructor and stops at its first player dependency before global settings or a live world are read. This proves selection of that branch, not a full generic-world bootstrap. No provider shadow, live server, or world mutation is used. Full suite and artifact results are recorded in `build/route-context-evidence/full.log` and `test-summary.json`.

## Separate remaining problem

This fix does not provide the missing descent/cleanup route. In this run the bot is four blocks above the ground on the upper platform; the normal maximum unprotected fall is three. `GoalBreak` rejects stances above the target, and the lane driver returns immediately for `electedCell == null`, so pure break/cleanup work has no helper-lane escalation. Those constraints require a separate coherent work-target/cleanup design and isolated comparison. Passing this regression must not be claimed as completing the144 plateau or the larger Basalt farm.
