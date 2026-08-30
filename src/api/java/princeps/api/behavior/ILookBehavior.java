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

package princeps.api.behavior;

import princeps.api.Settings;
import princeps.api.behavior.look.AimIntent;
import princeps.api.behavior.look.IAimProcessor;
import princeps.api.utils.Rotation;

/**
 * @author Brady
 * @since 9/23/2018
 */
public interface ILookBehavior extends IBehavior {

    /**
     * Updates the current {@link ILookBehavior} target to target the specified rotations on the next tick. If any sort
     * of block interaction is required, {@code blockInteract} should be {@code true}. It is not guaranteed that the
     * rotations set by the caller will be the exact rotations expressed by the client (This is due to settings like
     * {@link Settings#randomLooking}). If the rotations produced by this behavior are required, then the
     * {@link #getAimProcessor() aim processor} should be used.
     *
     * @param rotation      The target rotations
     * @param blockInteract Whether the target rotations are needed for a block interaction
     */
    void updateTarget(Rotation rotation, boolean blockInteract);

    /**
     * Like {@link #updateTarget(Rotation, boolean)}, additionally signalling that the interaction this aim serves is
     * a block BREAK (never a place/use). Break aims may be shaped by the humanized bell-curve turn — the dig itself
     * stays gated on the live crosshair raytrace at the press site, so a slower aim only ever means a later dig,
     * never a wrong-block dig. The intent cannot be derived from the CLICK_LEFT input state: break sites correctly
     * press only after the crosshair has arrived, so input-derived detection is circular and would leave the
     * first-aim snap (the "flick") in place.
     *
     * @param rotation      The target rotations
     * @param blockInteract Whether the target rotations are needed for a block interaction
     * @param breakIntent   Whether this aim targets a block that is about to be broken
     */
    default void updateTarget(Rotation rotation, boolean blockInteract, boolean breakIntent) {
        this.updateTarget(rotation, blockInteract);
    }

    /**
     * Like {@link #updateTarget(Rotation, boolean, boolean)}, additionally signalling that this aim serves a block
     * placement and may therefore be turned through the humanized arc instead of snapping. New movement code should
     * prefer the enum overload: two booleans cannot express mutual exclusion in their type.
     *
     * @param rotation      The target rotations
     * @param blockInteract Whether the target rotations are needed for a block interaction
     * @param breakIntent   Whether this aim targets a block that is about to be broken
     * @param placeIntent   Whether this aim targets a placement
     */
    default void updateTarget(Rotation rotation, boolean blockInteract, boolean breakIntent, boolean placeIntent) {
        this.updateTarget(rotation, blockInteract, breakIntent);
    }

    /**
     * V3-only deterministic execution intent. Implementations must keep the older overloads' behaviour unchanged;
     * this flag suppresses execution-time sampling only for the explicitly marked target.
     *
     * @param deterministicIntent whether RNG-derived tremor/curve variance must not affect this target
     */
    default void updateTarget(Rotation rotation, boolean blockInteract, boolean breakIntent, boolean placeIntent,
                              boolean deterministicIntent) {
        this.updateTarget(rotation, blockInteract, breakIntent, placeIntent);
    }

    /**
     * Intent-safe form used by movement and other interaction pipelines. Unlike the legacy boolean overloads, this
     * cannot accidentally describe the same target as both a break and a placement.
     */
    default void updateTarget(Rotation rotation, boolean blockInteract, AimIntent intent) {
        AimIntent safeIntent = intent == null ? AimIntent.NONE : intent;
        this.updateTarget(rotation, blockInteract,
                safeIntent == AimIntent.BREAK, safeIntent == AimIntent.PLACE);
    }

    /** Intent-safe form with deterministic execution sampling control. */
    default void updateTarget(Rotation rotation, boolean blockInteract, AimIntent intent,
                              boolean deterministicIntent) {
        AimIntent safeIntent = intent == null ? AimIntent.NONE : intent;
        this.updateTarget(rotation, blockInteract,
                safeIntent == AimIntent.BREAK, safeIntent == AimIntent.PLACE, deterministicIntent);
    }

    /**
     * The aim processor instance for this {@link ILookBehavior}, which is responsible for applying additional,
     * deterministic transformations to the target rotation set by {@link #updateTarget}.
     *
     * @return The aim processor
     * @see IAimProcessor#fork
     */
    IAimProcessor getAimProcessor();

    /**
     * The rotation the SERVER currently believes this player has -- i.e. the one in the last outgoing
     * {@code ServerboundMovePlayerPacket.Rot/PosRot}. Empty until the first such packet of a session.
     *
     * <p><b>DIES IST DIE ROTATION, DIE ZAEHLT, sobald eine Platzierung eine Richtung hat</b>, und der Unterschied
     * ist gemessen und gross. Vanilla leitet die Orientierung der gerichteten Blockfamilie (Kolben, Beobachter,
     * Spender) aus {@code getNearestLookingDirection()} ab, und das liest den Blickvektor der ENTITAET auf der
     * Seite, die den Blockzustand ausrechnet. Im {@code ServerboundUseItemOnPacket} steht KEINE Rotation -- nur
     * getroffener Block, Flaeche und Cursor. Der Server nimmt seine Rotation also zwangslaeufig aus dem zuletzt
     * EMPFANGENEN Bewegungspaket, und das Klickpaket verlaesst den Client davor.
     *
     * <p>GEMESSEN, basalt-Lauf 20260807-160142, 1076 Klicks von beiden Seiten mitgeschrieben und gegeneinander
     * gehalten: <b>234 Abweichungen zwischen dem, was der Client sah, und dem, was der Server sah -- und bei acht
     * davon kippte die abgeleitete Richtung.</b> Beispiele: 114,-61,91 Client -44,5 Grad (south) gegen Server
     * -70,1 Grad (east); 84,-59,101 Client -90,0 (down) gegen Server -74,3 (east), 33,9 Grad Unterschied im Pitch;
     * 105,-61,75 Client -134,8 (east) gegen Server -146,7 (north) -- genau die 45-Grad-Grenze zwischen Ost und
     * Nord. Spitzenabweichung im Lauf: 70 Grad Yaw. Das ist keine Rundung, das ist ein ganzer Tick Drehung.
     *
     * <p>Wer eine Orientierung erzwingen will, muss deshalb gegen DIESEN Wert simulieren und nicht gegen
     * {@code player.getYRot()}: erst wenn die bereits GESENDETE Rotation den gewollten Block erzeugt, darf der
     * Klick raus. Das kostet hoechstens einen Tick Wartezeit und macht die Platzierung deterministisch statt
     * wahrscheinlich -- was der Eigentuemer als Anforderung gesetzt hat: "Platzierungen duerfen nie fehlschlagen.
     * Es sind nur wenige Dinge noetig, die man mit hundertprozentiger Sicherheit erfuellen kann: Position und
     * wichtiger Blickrichtung und Winkel."
     *
     * @return the last rotation actually sent to the server, or empty if none has been sent yet
     */
    java.util.Optional<Rotation> getRotationTheServerHas();

    /**
     * Has the rotation stopped changing? True when the last two rotation packets carried the SAME angles.
     *
     * <p><b>DIES IST DIE BEDINGUNG, UNTER DER EINE ORIENTIERTE PLATZIERUNG VORHERSAGBAR IST</b>, und der Grund
     * dafuer ist gemessen und nicht hergeleitet. Der Server wertet einen Bauklick nicht aus, wenn er ANKOMMT,
     * sondern wenn er DRANKOMMT: {@code PacketUtils.ensureRunningOnSameThread} plant das Paket vom Netzwerk- auf
     * den Server-Thread um. Zwischen diesen beiden Zeitpunkten wendet der Server die Bewegungspakete an, die
     * ebenfalls in der Warteschlange stehen -- der Blockzustand entsteht also aus einer Rotation, die der Client
     * unter Umstaenden erst NACH dem Klick gesendet hat.
     *
     * <p>Belegt mit einem Mixin auf {@code handleUseItemOn}, das den Threadnamen mitdruckt: 24 Zeilen
     * {@code thread=Netty} gegen 24 {@code thread=Server}, exakt halbe-halbe -- ein Paket, zwei Auswertungen.
     * Fuer die Zelle 77,-59,75 (gewollt {@code sticky_piston[facing=up]}) las die Netty-Zeile yaw 123,360 /
     * pitch 41,463 (senkrechte Komponente 0,662 gegen 0,626 waagerecht, also Blick nach unten -> Kolben zeigt up,
     * genau wie der Client geprueft hatte) und die Server-Zeile yaw 81,362 / pitch 27,859 (0,874 waagerecht gegen
     * 0,467 senkrecht, Blick nach west -> Kolben zeigt east). East steht in der Welt.
     *
     * <p>Daraus folgt, was NICHT reicht: weder eine genauere Zielpunktwahl (clientseitig, im Repo bereits als
     * wirkungslos gemessen) noch die zuletzt GESENDETE Rotation -- massgeblich ist die naechste. Was reicht, ist
     * Ruhe: aendert sich die gesendete Rotation nicht mehr, dann ist "jetzt" gleich "gleich", und die Orientierung
     * ist deterministisch. Kosten: ein Tick.
     */
    boolean rotationHasSettled();

    /**
     * Die Rotation, mit der der SERVER eine Platzierung ausrechnen wird -- als Mischung, nicht als Momentaufnahme.
     *
     * <p>WARTEN WAR DER FALSCHE ANSATZ, und das ist gemessen: {@link #rotationHasSettled()} als Vorbedingung fuer
     * orientierte Bloecke kostete auf basalt 2740 -> 1023 gesetzte Zellen, 84,1 -> 31,4 Bloecke pro Minute. Der
     * Bot stand richtig, aber er stand.
     *
     * <p>Es geht exakt, statt abzuwarten. Die Server-Zeile im Moment der Ausfuehrung sagt woertlich, welche zwei
     * Groessen eingehen:
     * <pre>
     *   yaw=127,616  pitch=43,717   viewYRot=85,819   viewXRot=43,717
     *                                ^ der VORIGE Yaw   ^ der AKTUELLE Pitch
     * </pre>
     * {@code getViewVector} liest ueber {@code LivingEntity.getViewYRot} den KOPF, und der wird erst im
     * Entity-Tick nachgezogen -- er traegt also den Yaw des vorigen Bewegungspakets. Der Pitch dagegen ist
     * aktuell. Wer diese Mischung simuliert, weiss vorher, was entsteht, und muss auf nichts warten.
     *
     * @return Yaw des vorletzten, Pitch des letzten gesendeten Pakets; leer, solange es keine zwei gibt
     */
    java.util.Optional<Rotation> getRotationTheServerWillUse();

    /**
     * Alle Rotationen, mit denen der Server eine Platzierung ausrechnen KOENNTE -- der aktuelle Pitch, kombiniert
     * mit jedem der zuletzt gesendeten Yaws.
     *
     * <p>WARUM EINE LISTE UND NICHT DIE EINE MISCHUNG. {@link #getRotationTheServerWillUse()} ist nachweislich
     * richtig: fuenf Stichproben aus dem basalt-Lauf 20260807-2308, Client-Vorhersage gegen die Server-Zeile im
     * Moment der Ausfuehrung, jedes Mal auf drei Nachkommastellen identisch. Sie ist nur nicht VOLLSTAENDIG.
     *
     * <p>Zelle 114,-59,74, gewollt {@code sticky_piston[facing=up]}, gesetzt wurde {@code facing=east} -- nach
     * 1975 fehlerfreien Zellen. Dort hatte der Client denselben Yaw zweimal gesendet, die Ein-Schritt-Mischung
     * war also gleich der aktuellen Rotation und die Pruefung fand keinen Widerspruch. Mit dieser Rotation
     * (yaw 129,268 / pitch 41,694) waere das Ergebnis auch richtig gewesen: senkrechte Komponente 0,665 gegen
     * 0,578 waagerecht, also Blick nach unten, also Kolben nach up. Der Server muss demnach einen Yaw benutzt
     * haben, der noch weiter zurueckliegt als eine Sendung -- der Kopfverzug ist nicht fest bei eins.
     *
     * <p>Die Tiefe zu erraten waere Glueckssache, und auf Ruhe zu warten war schon einmal die falsche Antwort
     * (84,1 -> 31,4 Bloecke pro Minute). Alle jungen Kandidaten zu pruefen ist dagegen billig -- eine Simulation
     * je Kandidat, kein Tick Wartezeit -- und deckt jede Verzugstiefe bis zur Listenlaenge ab. Der Klick geht nur
     * raus, wenn das Ergebnis unter allen gleich ist; wenn nicht, war es ohnehin nicht vorhersagbar.
     *
     * @return junge Kandidaten, neueste zuerst; leer, solange nichts gesendet wurde
     */
    java.util.List<Rotation> getRotationsTheServerMightUse();
}
