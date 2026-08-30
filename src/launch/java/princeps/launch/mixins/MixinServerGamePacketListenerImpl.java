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

package princeps.launch.mixins;

import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DER ABGLEICH, DEN DER OWNER VERLANGT HAT: was sieht der SERVER in dem Moment, in dem er den Bauklick verarbeitet?
 *
 * <p>Wir kontrollieren den Bench-Server selbst, und Princeps laeuft dort mit ({@code environment: "*"}), also laesst
 * sich die Frage direkt beantworten statt sie abzuleiten. Genau eine Zeile je Bauklick, geschrieben an der Stelle,
 * an der der Server das Paket entgegennimmt und BEVOR er daraus einen Blockzustand rechnet.
 *
 * <p><b>Warum diese Zeile die Frage entscheidet.</b> Die Orientierung der gerichteten Blockfamilie (Kolben,
 * Beobachter, Spender, Trichter) entsteht in vanilla aus {@code BlockPlaceContext.getNearestLookingDirection()},
 * und das liest den Blickvektor der ENTITAET auf dem Server. Im
 * {@link ServerboundUseItemOnPacket} steht keine Rotation -- nur der Trefferpunkt. Die Rotation, mit der der Server
 * rechnet, stammt also aus dem zuletzt empfangenen Bewegungspaket. Ob die mit der uebereinstimmt, die der Client im
 * selben Tick simuliert hat, ist genau die offene Frage; hier steht die Antwort.
 *
 * <p>Reines Instrument, kein Verhalten: der Inject sitzt am Kopf, aendert nichts und kann nichts abbrechen.
 * Die Zeile traegt das Praefix {@code [SRVAIM]}, damit sie sich mit einem grep gegen die {@code [CLIAIM]}-Zeile des
 * Clients halten laesst.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerImpl {

    @Shadow
    public ServerPlayer player;

    // require = 1: die Mixin-Konfiguration steht auf defaultRequire 0, ein verfehltes Ziel waere also STILL --
    // und ein Instrument, das schweigend nicht misst, ist schlimmer als keines. Trifft der Name nicht, soll der
    // Server beim Start lautstark scheitern.
    @Inject(method = "handleUseItemOn", at = @At("HEAD"), require = 1)
    private void princeps$logServerAimOnUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        final ServerPlayer p = this.player;
        if (p == null) {
            return;
        }
        try {
            final BlockHitResult hit = packet.getHitResult();
            final Vec3 look = p.getViewVector(1.0F);
            // Genau die Ableitung, die vanilla fuer die gerichtete Familie benutzt: die groesste Komponente des
            // Blickvektors gewinnt. Mitgedruckt, damit man nicht aus Yaw/Pitch zurueckrechnen muss.
            final Direction nearest = Direction.getApproximateNearest(look.x, look.y, look.z);
            // DER THREADNAME ENTSCHEIDET EINE FRAGE, DIE SONST NICHT ZU ENTSCHEIDEN IST. Jedes Ziel erzeugt hier
            // genau ZWEI Zeilen, und bei einem Viertel davon unterscheiden sie sich in Yaw und Position. Das kann
            // zweierlei heissen: entweder schickt der Client wirklich zwei Pakete, oder es ist EIN Paket, das
            // vanilla zweimal durch diese Methode schickt -- PacketUtils.ensureRunningOnSameThread wirft beim
            // ersten Aufruf auf dem Netty-Thread eine RunningOnDifferentThreadException und plant die Verarbeitung
            // auf den Server-Thread um, wo die Methode ein zweites Mal von vorn beginnt. Ein @At("HEAD")-Inject
            // sieht beide Male.
            //
            // Der Unterschied ist gross: im ersten Fall gibt es einen ungeprueften Klick, im zweiten wird derselbe
            // Klick zu zwei verschiedenen Zeitpunkten ausgewertet -- und massgeblich ist der SPAETERE, weil dort
            // der Blockzustand entsteht. Zwei verschiedene Threadnamen beweisen den zweiten Fall.
            System.out.println("[SRVAIM]"
                    + " thread=" + Thread.currentThread().getName()
                    + " tick=" + p.level().getGameTime()
                    + " yaw=" + String.format(java.util.Locale.ROOT, "%.3f", p.getYRot())
                    + " pitch=" + String.format(java.util.Locale.ROOT, "%.3f", p.getXRot())
                    // DIE GROESSE, DIE WIRKLICH ENTSCHEIDET -- und der Verdacht, den diese Zeile pruefen soll.
                    // getNearestLookingDirection() geht ueber getViewVector, und LivingEntity leitet die aus
                    // getViewYRot/getViewXRot ab, also aus der KOPFrotation. Gemessen an Zelle 72,-59,78:
                    // yaw und pitch stimmten zwischen Client und Server exakt ueberein (-131,581 / 44,785,
                    // senkrechte Achse dominant mit 33 Prozent Vorsprung), und der Server leitete trotzdem
                    // 'south' ab -- was nicht einmal zum Vorzeichen seines eigenen z passt. Wenn yHeadRot hier
                    // von yaw abweicht, ist das die Erklaerung, und der Client modelliert die falsche Groesse.
                    + " headYaw=" + String.format(java.util.Locale.ROOT, "%.3f", p.getYHeadRot())
                    + " viewYRot=" + String.format(java.util.Locale.ROOT, "%.3f", p.getViewYRot(1.0F))
                    + " viewXRot=" + String.format(java.util.Locale.ROOT, "%.3f", p.getViewXRot(1.0F))
                    + " nearest=" + nearest
                    + " opposite=" + nearest.getOpposite()
                    + " pos=" + String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", p.getX(), p.getY(), p.getZ())
                    + " against=" + hit.getBlockPos().getX() + "," + hit.getBlockPos().getY()
                    + "," + hit.getBlockPos().getZ()
                    + " face=" + hit.getDirection()
                    + " target=" + hit.getBlockPos().relative(hit.getDirection()).getX()
                    + "," + hit.getBlockPos().relative(hit.getDirection()).getY()
                    + "," + hit.getBlockPos().relative(hit.getDirection()).getZ()
                    + " cursor=" + String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f",
                            hit.getLocation().x, hit.getLocation().y, hit.getLocation().z)
                    + " held=" + p.getMainHandItem().getItem());
        } catch (RuntimeException ignored) {
            // Ein Instrument darf den Server nicht mitnehmen.
        }
    }
}
