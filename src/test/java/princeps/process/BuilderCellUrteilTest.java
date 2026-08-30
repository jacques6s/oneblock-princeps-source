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

package princeps.process;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Die Eigenschaften des Zellurteils, die ohne laufendes Minecraft pruefbar sind -- und das ist genau der Punkt der
 * Uebung.
 *
 * <p>Der ganze Entscheidungskern des Bauers haengt heute an einem Bench-Lauf von zwoelf Minuten, um den sich zwei
 * Sitzungen streiten. Eine TOTALE Funktion ueber Zelle und Weltzustand ist dagegen aussagenlogisch pruefbar: sie hat
 * endlich viele Ausgaenge, und ueber die kann man Zusicherungen schreiben, die kein Spiel brauchen. Diese Datei
 * haelt fest, was sich so festhalten laesst; die Wirkung auf einen echten Bau bleibt eine Messung.
 */
public class BuilderCellUrteilTest {

    /**
     * DREI FAELLE, KEIN VIERTER. Die Versiegelung ist die tragende Zusicherung des ganzen Umbaus: solange der
     * Aufrufer alle Faelle kennt, kann er immer entscheiden, und dann braucht er keine Uhr. Kommt spaeter ein
     * vierter Fall dazu, ohne dass die Aufrufer ihn behandeln, faellt es hier auf und nicht erst in einem Lauf.
     */
    @Test
    public void esGibtGenauDreiAusgaenge() {
        Class<?>[] erlaubt = BuilderProcess.CellUrteil.class.getPermittedSubclasses();
        assertNotNull("CellUrteil muss versiegelt sein -- sonst ist 'vollstaendig' eine Behauptung", erlaubt);
        List<String> namen = Arrays.stream(erlaubt)
                .map(Class::getSimpleName)
                .sorted()
                .collect(Collectors.toList());
        assertEquals("genau Setzen, Parken, Unbekannt", Arrays.asList("Parken", "Setzen", "Unbekannt"), namen);
    }

    /**
     * Jeder Ausgang ist ein Record, also unveraenderlich und mit Inhalt.
     *
     * <p>Nicht kosmetisch: ein Urteil, das der Empfaenger noch veraendern kann, ist wieder eine Meinung statt einer
     * Feststellung -- und zwei Meinungen ueber dieselbe Zelle sind der Fehler, den dieses Repo mehrfach bezahlt hat
     * (siehe die Notiz an {@code aimPointsOnFace}: "one method for both callers by construction").
     */
    @Test
    public void jederAusgangIstUnveraenderlich() {
        for (Class<?> fall : BuilderProcess.CellUrteil.class.getPermittedSubclasses()) {
            assertTrue(fall.getSimpleName() + " muss ein Record sein", fall.isRecord());
        }
    }

    /**
     * PARKEN traegt immer einen Grund aus der Spezifikation -- A, B oder C -- und einen Beleg im Klartext.
     *
     * <p>Ein Park ohne Grund waere genau das, was die Spezifikation verbietet: eine Zelle, die stillgelegt wird,
     * ohne dass jemand sagen kann, welches Ereignis sie wieder wecken darf. Der Waechter haengt an diesem Grund.
     */
    @Test
    public void parkenNenntGrundUndBeleg() {
        Class<?> parken = Arrays.stream(BuilderProcess.CellUrteil.class.getPermittedSubclasses())
                .filter(c -> c.getSimpleName().equals("Parken"))
                .findFirst().orElseThrow(AssertionError::new);
        List<String> felder = Arrays.stream(parken.getRecordComponents())
                .map(c -> c.getType().getSimpleName())
                .collect(Collectors.toList());
        assertEquals("Grund und Beleg", Arrays.asList("ParkReason", "String"), felder);
    }

    /**
     * UNBEKANNT muss sagen, WARUM nichts festgestellt wurde.
     *
     * <p>Das ist der Fall, der bisher als blankes {@code null} auftrat und mit vier echten Aussagen verwechselt
     * wurde -- daraus sind die sieben Wachhunde entstanden. Ein Unbekannt ohne Begruendung waere derselbe Fehler
     * mit neuem Namen.
     */
    @Test
    public void unbekanntNenntSeinenGrund() {
        Class<?> unbekannt = Arrays.stream(BuilderProcess.CellUrteil.class.getPermittedSubclasses())
                .filter(c -> c.getSimpleName().equals("Unbekannt"))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(1, unbekannt.getRecordComponents().length);
        assertEquals("String", unbekannt.getRecordComponents()[0].getType().getSimpleName());
    }

    /**
     * ES GIBT DIE ENTSCHEIDUNG NUR EINMAL.
     *
     * <p>{@code placementGoal} darf keine eigene Untersuchung mehr sein, sondern nur noch die alte Sicht auf
     * {@code urteileUeber} -- sonst driften die beiden auseinander und der Schattenbetrieb misst seinen eigenen
     * Zwilling statt des Systems. Geprueft wird, dass beide existieren und dass die lesende Form angeboten wird,
     * denn ohne sie muesste der Schatten Budget verbrauchen, um zu beobachten.
     */
    @Test
    public void esGibtEineUntersuchungUndEineLesendeForm() {
        List<Method> urteile = Arrays.stream(BuilderProcess.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("urteileUeber"))
                .collect(Collectors.toList());
        assertEquals("eine bequeme und eine parametrisierte Form", 2, urteile.size());
        assertTrue("es muss eine Form mit dem Nur-Lesen-Schalter geben",
                urteile.stream().anyMatch(m -> m.getParameterCount() == 3
                        && m.getParameterTypes()[2] == boolean.class));
        for (Method m : urteile) {
            assertEquals("beide Formen liefern ein vollstaendiges Urteil",
                    BuilderProcess.CellUrteil.class, m.getReturnType());
        }

        Method[] ziele = Arrays.stream(BuilderProcess.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("placementGoal"))
                .toArray(Method[]::new);
        assertEquals("placementGoal existiert genau einmal", 1, ziele.length);
        assertTrue("placementGoal bleibt privat -- es ist nur noch die alte Sicht",
                Modifier.isPrivate(ziele[0].getModifiers()));
    }

    /**
     * DER SCHATTEN ENTSCHEIDET NICHTS.
     *
     * <p>Er darf zaehlen und schreiben, aber nichts zurueckgeben, das der Bau verwenden koennte. Ein Beobachter
     * mit Rueckgabewert wird frueher oder spaeter angezapft, und dann ist aus der Messung eine zweite Meinung
     * geworden, bevor irgendjemand sie gemessen hat.
     */
    @Test
    public void derSchattenGibtNichtsZurueck() {
        Method schatten = Arrays.stream(BuilderProcess.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("schattenUrteil"))
                .findFirst().orElseThrow(() -> new AssertionError("schattenUrteil fehlt"));
        assertEquals("der Schatten darf nichts liefern", void.class, schatten.getReturnType());
        assertTrue("und er bleibt privat", Modifier.isPrivate(schatten.getModifiers()));
        assertFalse("er ist kein statischer Helfer, er zaehlt pro Lauf mit",
                Modifier.isStatic(schatten.getModifiers()));
    }
}
