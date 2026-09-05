# Unveraenderliches Ziel

`run/schematics/etz-basalt.litematic` (15 004 Zellen, 63x18x63) wird vom V3-Builder autonom und
vollstaendig gebaut.

## Harte Akzeptanzkriterien (der Bench entscheidet, nicht ein Agent)

1. `autonomy\gate.ps1 -Stage full` liefert `verdict=SUCCESS`.
2. Das Server-Audit bestaetigt **alle** Zellen (`N/N cells confirmed`). Ein `SUCCESS`, das der Server
   nicht bestaetigt, ist `CLIENT_ONLY` und gilt als Fehlschlag.
3. `autonomy\gate.ps1 -Stage ringbig` bleibt `SUCCESS`, `divergences=0`, `replans=0`.
4. `autonomy\gate.ps1 -Stage tests` ist vollstaendig gruen.
5. Die Zellzahl je Ebene im Dry Run ist nicht gesunken.
6. Keine geschuetzte Datei (`autonomy/protected.txt`) wurde veraendert.
7. **Kein Fremdblock im Bauvolumen.** An jeder Stelle, an der die Schematic Luft vorsieht, ist Luft.
   Insbesondere sind **alle Geruest- und Hilfsbloecke (Cobblestone) restlos abgebaut**, bevor ein Lauf
   als fertig gilt. Ein vergessener Cobblestone in der Luft macht die Farm kaputt und ist ein
   Fehlschlag -- auch wenn jede von der Vorlage benannte Zelle korrekt steht.
   *Hinweis: das heutige Server-Audit prueft nur Zellen, die die Vorlage benennt. Es ist fuer
   Fremdbloecke BLIND. Dieses Kriterium braucht eine eigene Pruefung.*

## Ausdruecklich KEIN Erfolg

- Ein Lauf, der mit `STALLED`, `INACTIVE` oder `CLIENT_ONLY` endet.
- Ein Fix, dessen Wirkung nur plausibel und nicht gemessen ist.
- Ein Test, der an das neue Verhalten angepasst wurde statt umgekehrt.
- `ringbig` gruen und Basalt kaputt.
- Eine Verbesserung, die nur in einem einzigen Lauf sichtbar war.
