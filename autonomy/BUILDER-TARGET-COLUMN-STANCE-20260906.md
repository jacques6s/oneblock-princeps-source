# Placement stances in the target column

Base: `f0a371ab4ac4a6f2eed99b1b9d86afd8692fbb4e`. This is an isolated engine change;
it does not change a live world, placement permissions, or the normal client.

The Main run `basalt65-supportdoor220-20260906-01` stopped at 4,272 correct cells
with repeated `NO_STANCE`. Its saved foundation was stone and both desired door
halves were air. The unchanged neighboring dirt and a chest above it obstruct the
apparently convenient raised west stance. The trace's “vanilla refused” samples
belong to a separate hypothetical scaffold in the upper door cell; those samples
do not establish the reason for the original stance rejection.

`placementStancesFor` excluded every candidate in the target column at offsets
−1, 0 and +1 before asking vanilla. Sharing a block cell does not imply collision:
a closed door panel is 3/16 wide, while a centered 0.6-wide player does not touch it.
The change removes that blanket exclusion. The original candidate ordering,
145-evaluation budget per pass, standability, material, reach, ray, hypothetical
body, vanilla item-placement and exact-state gates remain in place. The ordinary
three-height candidate window has 147 positions instead of 144.

## Controlled evidence

All evidence is under ignored `build/placement-stance-evidence/`.

* `red/`: original production source, 10 tests, one assertion failure. The actual
  `placementStancesFor` method does not offer the vanilla-valid target cell.
* `green/`: seven related suites, 79 tests, zero failures/errors/skips.
* `full-xml/`: 94 suites, 786 tests: 782 pass and four existing TraceReplay skips.
  Basalt is 7/7 with the pinned original fixture; the ten new tests all pass.
* `controlled-red.log`, `final-targeted-green.log`, `full-green.log` retain the runs.

The candidate test observes the real sampler through its existing allowed
predicate. A flooded input prevents unrelated global-client/provider construction;
it does not replace a successful stance result. Separately, geometry tests call the
actual shared aim-point generator, actual `AimProcessor.peekRotationExact`, vanilla
world clip, and the registered item's actual `BlockItem.getPlacementState` via
reflection. They also call the builder's real simulation and check restoration.
The headless builder helper takes its documented non-Mixin approximation; the
separate real item call is essential to the collision evidence.

Counterexamples cover a full cube, actual panel/body overlap, another entity in
the panel, absent permanent support, occupied upper half, missing hotbar stock,
and exceptional restoration of position, yaw, pitch, head yaw and FlowCam suspension.
Rotated examples cover all four horizontal facings and retain exact hinge matching;
they do not claim every hinge is attainable from every centered pose.

The first related run was 79/1: an existing material-verdict fixture explicitly
relied on the excluded target column and supplied no world border. That fixture
now floods the target before all material steps, making its stated “all stances
in water” premise true while the world remains unchanged across refill. No
production null guard was added. Its XML remains in `first-related-failure/`.
Earlier diagnostic/compiler failures are retained separately and are not counted
as the controlled RED.

## Reproduction inputs and limits

`tested-door-geometry.json` describes the small tested positive witness. With feet
at relative `(0.5, 0, 0.5)`, body 0.6 × 1.8, eye height 1.27 and sensitivity 0.5,
the real quantized ray hits the floor's upper face at approximately
`(0.90154259, 0, 0.5)`, yaw −90.000015°, pitch 72.450005°. Vanilla returns the exact
east-facing, left-hinge, closed, unpowered lower door.

`main-door-neighborhood-294.json` preserves the complete raw Main neighborhood
relative to the lower door, bounds `[-3,-2,-3]..[3,3,3]`, from both identical saved
regions with SHA-256 `a6f4cc29c2d5e93c5b5cde6595da853751443ee52536735efc722a51be179ad1`.
The exporter pins the existing decoder, NBT reader and original schematic.
`main-original-and-fixed-candidates.json` enumerates all old/new candidates with
their actual feet/head/floor inputs. Non-air is not automatically called blocked.

No full onTick/A-star/physics reproduction or game acceptance is claimed here.
In particular, the small positive witness does not rule out alternative outside
stances in an enlarged test arena. An isolated old-versus-new game comparison must
start outside the valid target-cell stance: a helper starting directly on the
lower-door anchor could let the old live-click path place immediately, bypassing
the very candidate-selection failure under test.

The frozen runtime/API, source and evidence hashes are in `final-artifacts.json`.
No live-world or administration command was issued by this task.
