"""
navbench — high-fidelity simulator of the Princeps navigation/look pipeline.

Faithfully replays, per tick, the exact chain the mod runs on the ground with freeLook=false
(so movement direction == applied yaw):

  path -> pursuit steer (far-carrot gaze + near-carrot W/A/D octant, hysteresis)
       -> ballistic proportional turn toward the gaze  (LookBehavior.proportionalStep)
       -> + fixation/saccade wander + micro-tremor      (only if enabled in the profile)
       -> hard turn-envelope cap (70 deg/tick)           (LookBehavior.hardCap)
       -> integer mouse-count quantization               (LookBehavior.calculateMouseMove)
       -> MC ground movement physics (accel + friction)  (LivingEntity.travel order)

Every tick it records a full TickRecord: the SENT packet fields (yaw, pitch, sprint, WASD),
the body coordinate (x,y,z), the view direction, and internal steering state — so the analyzer
can read "alle Pakete, jede Koordinate, jede Blickrichtung" and test for patterns / smoothness.

The constants mirror the deployed Princeps values; MC physics uses the real travel() order
  v += accel*dir ; pos += v ; v *= friction
which reproduces the correct sprint steady state (~0.2863 b/t) and the true acceleration ramp
(this matters for jerk/smoothness fidelity — a coarser model hides real discontinuities).
"""
import math
from dataclasses import dataclass, field

# ----------------------------- MC physics -----------------------------
GROUND_SLIP = 0.6
FRICTION = GROUND_SLIP * 0.91                 # 0.546
WALK_SPEED, SPRINT_MULT = 0.1, 1.3
# getFrictionInfluencedSpeed = movementSpeed * 0.21600002 / slip^3 ; slip^3 == 0.216 -> factor 1.0
SPRINT_ACCEL = WALK_SPEED * SPRINT_MULT       # 0.13   (per-tick input acceleration, sprinting)
WALK_ACCEL = WALK_SPEED                        # 0.10   (no forward impulse -> no sprint)

# ----------------------------- look pipeline --------------------------
@dataclass
class Profile:
    name: str
    gain: float
    min_step: float
    max_step: float
    hard_cap: float = 70.0
    drift_deg: float = 0.0        # saccade wander amplitude (0 = parallel-session clean look)
    tremor_deg: float = 0.0       # micro-tremor amplitude (0 = clean look)
    gaze_ahead: float = 3.2
    track_ahead: float = 0.7
    sensitivity: float = 0.5      # MC mouse sensitivity -> quantization step
    engage_deg: float = 25.0      # strafe engage threshold
    release_deg: float = 12.0     # strafe release threshold (hysteresis)
    wander_fade_lo: float = 20.0
    wander_fade_hi: float = 45.0
    steer: str = "octant"         # "octant" = current; "crosstrack"; "human" = crosstrack + curvature-aware speed
    xt_engage: float = 0.30       # crosstrack strafe engage (blocks of lateral drift)
    xt_release: float = 0.12      # crosstrack strafe release (hysteresis)
    curve_slow: bool = False      # slow into sharp bends (human speed modulation) — enables tight yaw+W cornering
    discrete_speed: bool = False  # use MC's 3 real speed levels (sprint/walk/sneak) instead of a continuous factor
    curve_lookahead: float = 2.4  # blocks ahead to measure upcoming path curvature
    min_speed_factor: float = 0.42  # hardest slow-in (fraction of sprint accel) at the sharpest bend
    boundary_hyst: float = 20.0   # deg margin to switch octant (SHIPPED anti-chatter; humanizedSteeringHysteresis)
    cruise_yaw_cap: float = 999.0   # hard smoothness cap on the per-tick sent yaw change (deg)
    cruise_pitch_cap: float = 999.0 # hard smoothness cap on the per-tick sent pitch change (deg)

DEPLOYED = Profile("deployed", gain=0.55, min_step=8.0, max_step=55.0, drift_deg=3.0, tremor_deg=0.14,
                   cruise_yaw_cap=9.0, cruise_pitch_cap=15.0, curve_slow=True, discrete_speed=True)
INTENDED = Profile("intended", gain=0.22, min_step=1.6, max_step=24.0, drift_deg=0.0, tremor_deg=0.0,
                   cruise_yaw_cap=9.0, cruise_pitch_cap=15.0, curve_slow=True, discrete_speed=True)

def min_count(sens):
    f = sens * 0.6 + 0.2
    return f * f * f * 8.0 * 0.15   # mouseToAngle(1)

def wrap(a):
    return (a + 180.0) % 360.0 - 180.0

def prop_step(err, p):
    a = abs(err)
    clamped = max(p.min_step, min(a * p.gain, p.max_step))
    return math.copysign(min(a, clamped), err) if err else 0.0

def yaw_to(p, q):   # MC yaw: 0 = +z, 90 = -x
    return math.degrees(math.atan2(-(q[0] - p[0]), q[1] - p[1]))

OCTANTS = [0, 45, -45, 90, -90, 135, -135, 180]

# ----------------------------- path geometry --------------------------
def centers(nodes):
    return [(x + 0.5, z + 0.5) for x, z in nodes]

def project(pts, p, lo, hi):
    best = (1e18, lo, 0.0)
    for i in range(lo, min(hi, len(pts) - 1)):
        ax, az = pts[i]; bx, bz = pts[i + 1]
        dx, dz = bx - ax, bz - az; l2 = dx * dx + dz * dz
        t = 0.0 if l2 == 0 else max(0.0, min(1.0, ((p[0]-ax)*dx + (p[1]-az)*dz) / l2))
        qx, qz = ax + dx*t, az + dz*t
        d = (p[0]-qx)**2 + (p[1]-qz)**2
        if d < best[0]: best = (d, i, t)
    return best[1], best[2]

def advance(pts, i, t, end, ahead):
    cx = pts[i][0] + (pts[i+1][0]-pts[i][0]) * t
    cz = pts[i][1] + (pts[i+1][1]-pts[i][1]) * t
    j, rem = i, ahead
    while rem > 1e-9:
        bx, bz = pts[j+1]; seg = math.hypot(bx-cx, bz-cz)
        if seg >= rem:
            f = 0 if seg == 0 else rem/seg
            return (cx + (bx-cx)*f, cz + (bz-cz)*f)
        rem -= seg; cx, cz = bx, bz; j += 1
        if j >= end: return (cx, cz)
    return (cx, cz)

# ----------------------------- per-tick record ------------------------
@dataclass
class TickRecord:
    tick: int
    x: float; z: float           # body coordinate (y flat here)
    yaw: float; pitch: float     # SENT view direction
    dyaw: float                  # per-tick sent yaw delta (the packet delta)
    vx: float; vz: float         # velocity
    speed: float
    forward: bool; back: bool; left: bool; right: bool; sprint: bool  # WASD packet
    octant: int
    cross: float                 # cross-track error vs path
    node: int

@dataclass
class Trace:
    profile: str
    scenario: str
    records: list = field(default_factory=list)
    done: bool = False
    coverage: float = 0.0

# ----------------------------- the simulator --------------------------
class Wander:
    """fixation+saccade yaw wander + micro-tremor, matching LookBehavior.tick()."""
    def __init__(self, rng, p):
        self.rng, self.p = rng, p
        self.ou = self.tgt = 0.0; self.dwell = 0; self.tr = 0.0
    def tick(self):
        p = self.p
        if p.drift_deg > 0:
            if self.dwell <= 0:
                self.tgt = (self.rng.random()*2 - 1) * p.drift_deg
                self.dwell = 6 + int(self.rng.random()*26)
            self.dwell -= 1
            self.ou += (self.tgt - self.ou) * 0.28
        else:
            self.ou = 0.0
        if p.tremor_deg > 0:
            g = self.rng.random() + self.rng.random() + self.rng.random() - 1.5
            self.tr = max(-p.tremor_deg*3, min(p.tremor_deg*3, self.tr*(1-0.35) + p.tremor_deg*g))
        else:
            self.tr = 0.0
        return self.ou, self.tr

def simulate(nodes, profile, seed=0, flip_at=None, max_ticks=None):
    import random
    rng = random.Random(seed)
    p = profile
    mc = min_count(p.sensitivity)
    pts = centers(nodes)
    pos = [pts[0][0], pts[0][1]]
    v = [0.0, 0.0]
    yaw = yaw_to(pts[0], pts[1]); pitch = 0.0
    wander = Wander(rng, p)
    node_idx = 0; strafing = False; covered = set(); flipped = False
    held_octant = [0]   # the strafe octant currently held (for boundary hysteresis)
    needed = set(range(len(nodes)))
    tr = Trace(p.name, "custom")
    mt = max_ticks or (int(len(pts) * 4 / 0.28) + 300)
    for tick in range(mt):
        if flip_at is not None and tick >= flip_at and not flipped:
            fb0 = (math.floor(pos[0]), math.floor(pos[1]))
            nodes = list(reversed(nodes)); pts = centers(nodes); covered = set(); flipped = True
            node_idx = min(range(len(nodes)), key=lambda k: (nodes[k][0]-fb0[0])**2 + (nodes[k][1]-fb0[1])**2)
            needed = set(range(node_idx, len(nodes)))
        fb = (math.floor(pos[0]), math.floor(pos[1]))
        for k, n in enumerate(nodes):
            if fb == n: covered.add(k)
        for k in range(min(node_idx+4, len(nodes)-1), node_idx-1, -1):
            if fb == nodes[k]: node_idx = k; break
        if fb == nodes[-1] and (flip_at is None or flipped):
            tr.done = True; tr.coverage = len(covered & needed)/max(1, len(needed)); break

        end = min(len(pts)-1, node_idx+8)
        i, t = project(pts, pos, max(0, node_idx-1), end)
        ax, az = pts[i]; bx, bz = pts[i+1]
        segl = math.hypot(bx-ax, bz-az) or 1
        cross = ((pos[0]-ax)*(bz-az) - (pos[1]-az)*(bx-ax)) / segl

        far = advance(pts, i, t, end, p.gaze_ahead)
        near = advance(pts, i, t, end, p.track_ahead)
        prev_yaw = yaw

        # --- ballistic turn toward the far carrot, + wander (faded during active turns) ---
        ou, trem = wander.tick()
        gaze = yaw_to(pos, far)
        raw_err = abs(wrap(gaze - prev_yaw))
        wscale = 1.0 if raw_err <= p.wander_fade_lo else max(0.0, 1 - (raw_err - p.wander_fade_lo)/(p.wander_fade_hi - p.wander_fade_lo))
        desired = gaze + ou*wscale + trem
        step = prop_step(wrap(desired - prev_yaw), p)
        capped = max(-p.hard_cap, min(p.hard_cap, step))       # hard cap
        capped = max(-p.cruise_yaw_cap, min(p.cruise_yaw_cap, capped))   # tight smoothness cap (user: <=9 deg)
        qdelta = round(capped / mc) * mc                        # mouse-count quantize
        yaw = prev_yaw + qdelta
        dyaw = wrap(yaw - prev_yaw)

        # --- feet steering: choose the octant offset (relative to the applied yaw) ---
        if p.steer in ("crosstrack", "human"):
            # Human-like: the yaw turn + W already corner the body along the path. Strafe ONLY to null residual
            # lateral drift, and only via a FORWARD diagonal (W+A / W+D) so forward momentum + sprint are kept and
            # the movement-direction step is at most 45 deg (never a 90 deg pure-strafe or a backward jump).
            if strafing and abs(cross) < p.xt_release: strafing = False
            elif not strafing and abs(cross) >= p.xt_engage: strafing = True
            # cross>0 means the body is left of the path (see cross sign) -> steer right (W+D = -45); else W+A (+45)
            octo = (-45 if cross > 0 else 45) if strafing else 0
        else:
            # SHIPPED design: strafe engages on near-carrot bearing OR lateral cross-track drift (the drift trigger
            # actuates a forced +-45 toward the line when the bearing sits inside the octant-0 deadzone — matches
            # MovementHelper.moveAlongPath after the review fix; bearing-only rounding was a no-op there).
            rel = wrap(yaw_to(pos, near) - prev_yaw)
            bearing_wants = (abs(rel) >= p.release_deg) if strafing else (abs(rel) >= p.engage_deg)
            xt_wants = (abs(cross) > p.xt_release) if strafing else (abs(cross) >= p.xt_engage)
            strafing = bearing_wants or xt_wants
            if strafing and abs(rel) < 22.5 and abs(cross) > p.xt_release:
                octo = 45 if cross > 0 else -45
            else:
                octo = min(OCTANTS, key=lambda o: abs(wrap(rel - o))) if strafing else 0
            # BOUNDARY HYSTERESIS (the shipped anti-chatter): keep the currently-held octant unless a different one
            # is better by more than boundary_hyst deg — stops the rapid A<->D flip as the bearing hovers on a
            # 45-deg boundary. Matches Princeps MovementHelper.strafeToward.
            if p.boundary_hyst > 0 and strafing and held_octant[0] != 0 and octo != held_octant[0]:
                if abs(wrap(rel - octo)) > abs(wrap(rel - held_octant[0])) - p.boundary_hyst:
                    octo = held_octant[0]
            held_octant[0] = octo if strafing else 0
            if p.steer == "octant45" and octo:  # experimental: forward-diagonal cap (loses coverage — not shipped)
                octo = 45 if octo > 0 else -45

        fwd = octo in (0, 45, -45)
        back = octo in (180, 135, -135)
        left = octo in (45, 90, 135)
        right = octo in (-45, -90, -135)

        # --- curvature-aware speed (human slow-in / accelerate-out) ---
        speed_factor = 1.0
        if p.curve_slow:
            a0 = advance(pts, i, t, end, 0.2)
            a1 = advance(pts, i, t, end, 0.2 + p.curve_lookahead * 0.5)
            a2 = advance(pts, i, t, end, 0.2 + p.curve_lookahead)
            bend = abs(wrap(yaw_to(a1, a2) - yaw_to(a0, a1)))          # heading change over the lookahead
            if p.discrete_speed:
                # SHIPPED mechanic: release sprint -> walk (0.77) at a sharp bend; no sneak. Matches
                # MovementHelper.moveAlongPath humanizedSteeringCurveSlow / humanizedSteeringSlowBend.
                speed_factor = 0.77 if bend >= 22 else 1.0
            else:
                speed_factor = 1.0 - (1.0 - p.min_speed_factor) * min(1.0, bend / 90.0)
        sprint = fwd and speed_factor > 0.85   # only truly sprint when not slowing for a bend

        # --- MC ground physics: v += accel*dir ; pos += v ; v *= friction ---
        move_dir = math.radians(yaw + octo)
        dirx, dirz = -math.sin(move_dir), math.cos(move_dir)
        base_accel = (SPRINT_ACCEL if fwd else WALK_ACCEL) * speed_factor
        accel = base_accel
        v[0] += dirx * accel; v[1] += dirz * accel
        pos[0] += v[0]; pos[1] += v[1]
        v[0] *= FRICTION; v[1] *= FRICTION

        tr.records.append(TickRecord(
            tick=tick, x=pos[0], z=pos[1], yaw=yaw, pitch=pitch, dyaw=dyaw,
            vx=v[0], vz=v[1], speed=math.hypot(v[0], v[1]),
            forward=fwd, back=back, left=left, right=right, sprint=sprint,
            octant=octo, cross=cross, node=node_idx))
    else:
        tr.coverage = len(covered & needed)/max(1, len(needed))
    if not tr.records:
        tr.coverage = 0.0
    return tr
