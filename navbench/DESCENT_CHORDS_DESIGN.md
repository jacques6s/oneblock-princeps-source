# Descent-Chords — Implementations-Blueprint (Stand 07.07.2026)

**Status: bench-fertig, NICHT implementiert.** Gated auf (1) erfolgreichen Live-Test der 2D-Chord-Maschinerie
(`smoothPath`), (2) explizites Go des Users. Alle Zahlen und Regeln unten sind bench-abgeleitet
(`descent_potential.py`, `descent_executor_sim.py`) — die eine nicht-offensichtliche Regel wurde VOR jeder Zeile
Kern-Code gefunden.

## Warum (Wert, quantifiziert)

250 Rolling-Terrain-Maps (2.5D-Heightmap, MC-artige Step-1-Züge):

| Merge-Modus          | Knoten | Richtungswechsel | Weglänge |
|----------------------|-------:|-----------------:|---------:|
| Lattice (Baseline)   |    ±0% |              ±0% |     ±0%  |
| Flat (shipped heute) |    −5% |              ±0% |    −0.0% |
| **Descent**          | **−53%** |         **−39%** |    −0.7% |

- Der shipped Flat-Merger ist auf hügeligem Terrain fast nutzlos (genau das Live-Terrain des Users).
- Descent-Chords sind ein **Smoothness-/Natürlichkeits-Hebel** (39% weniger Ecken für Look+Pursuit,
  halb so viele Junction-Übergaben), KEIN Effizienz-Hebel (Wege sind in XZ schon fast gerade).
- Ascent bleibt bewusst außen vor: Abwärts = Walk-off ohne Input; Aufwärts bräuchte Sprungtiming pro Stufe.

## Machbarkeit (bewiesen)

`descent_executor_sim.py`: 2.5D-Chords durch die **shipped** Executor-Zustandsmaschine (Chord-aware +1/+2-Skips,
Fern-Skip-Chord-Ausschluss, noRewindBelow-Floor, Rekursions-Depth-Guard, Distanz-Gate 2.0/200 + 3.0, Timeout
cost+100) mit navsim-Pursuit inkl. XT-Strafe: **150/150 Maps, 0 Cancels, Guard nie gesättigt**, 22% der
Movements werden Chords.

## Die drei Säulen der Implementierung

1. **Merge-Prädikat** (`Path.postProcess`, analog `smoothFlatRuns`): mergebare Runs = MovementTraverse/
   MovementDiagonal/MovementDescend-Ketten mit **monoton nicht-steigendem y, Drop ≤ 1 pro XZ-Block**, leeres
   toBreak/toPlace. Pro **Swept-Zelle** (exakter Liang-Barsky-Supercover, `smoothPathSweepHalf`=0.65) wird die
   **Profilhöhe** aus dem nächstliegenden Original-Knoten abgeleitet und verifiziert:
   `canWalkOn(Boden auf Profilhöhe−1) && canWalkThrough(Körper+Kopf) && !avoidWalkingInto`, Abweichung der
   Säulenhöhe vom Profil **≤ 1** — sonst kein Merge (Klippe neben der Linie ⇒ Lattice bleibt).
2. **3D-Valid-Set** (`getValidPositions`): jede Swept-Zelle **auf ihrer Merge-Zeit-Profilhöhe** (BetterBlockPos
   mit y!). Containment = Füße exakt auf verifizierter (x, Profil-y, z)-Zelle. Executor-Gates werden damit
   erfüllt, nicht geschwächt (wie bei den 2D-Chords).
3. **SUCCESS-Regel (DIE Bench-Findung):** End-Region = Achsen-Projektion `s ≥ len − 0.5` **UND Füße-Zelle im
   eigenen 3D-Valid-Set (Profilhöhen-Mitgliedschaft)**. **KEINE separate End-Höhen-Gleichheit** — die naive
   Regel (exakte End-Höhe verlangen) strandete 8/150 Maps in Timeouts, weil End-Zellen NEBEN dest auf
   Rolling-Terrain legitim ±1 liegen. Gleiche Philosophie wie die 2D-Livelock-Regel: Was das Containment
   hinter dem Ende akzeptiert, MUSS succeeden.

## Ausführung (updateState)

- Steering: unverändert `MovementHelper.moveAlongPath` (Pursuit folgt der XZ-Projektion; y macht das Terrain).
- Walk-off-Drops: kein Input nötig; Sim modellierte ~3 Ticks reduzierte Kontrolle pro Drop — real übernimmt
  MC-Physik. Kein JUMP, kein Precise-Aim ⇒ LookBehavior-Smoothing bleibt aktiv (Fall-Blick-Band 70–80° greift
  NICHT: Drops ≤1 sind kein MovementFall).
- Kosten: `min(XZ-euklid · SPRINT + Σ Drop-Kosten (FALL_1_25_BLOCKS_COST-artig), Summe der Original-Movements)`
  — nie über der Lattice-Summe (Cost-Verify-Invariante wie 2D).
- Lifecycle: **`override(cost)` + `checkLoadedChunk(context)` im Erzeugungspfad** (die zwei 2D-Crash-Lektionen)
  + Audit aller nullable Member gegen Executor-Zugriffe VOR dem ersten Live-Lauf.

## Offene Punkte für die Implementierung

- MovementFall-Grenzen: Runs enden an Drops > 1 (die bleiben MovementFall mit eigenem Blick-Band).
- Wasser/Hazard-Säulen: `avoidWalkingInto` pro Profilhöhen-Zelle deckt Lava; Wasser-Zellen nicht mergen
  (Schwimm-Physik ≠ Walk-off).
- `smoothMaxChord`-Cap gilt unverändert (O(L³)-Schutz).
- Bench-Twin PFLICHT: `descent_executor_sim.py` auf die echte Merge-Implementierung spiegeln, `run_all.py`
  muss PASS bleiben; adversariales Review vor Deploy (2D-Präzedenz: 3 bestätigte Funde inkl. Crash).
