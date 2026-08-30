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

package princeps.process.builder.v3;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import java.util.ArrayList;
import java.util.List;

/**
 * The four lines of a sign, read out of a schematic's block-entity NBT.
 *
 * <p>Pure: a {@link CompoundTag} in, a list of strings out, no client, no world, no settings. That is what makes it
 * testable, and it is testable because getting it wrong is invisible — a decoder that silently yields four empty
 * strings produces a build that looks finished and has blank signs in it, which is exactly the failure
 * {@link BuildAction.WriteSign} exists to make impossible.
 *
 * <h2>Two on-disk shapes, both real</h2>
 *
 * <p>Since 1.20 a sign stores {@code front_text} and {@code back_text}, each a compound with a {@code messages} list
 * of four components. Current versions encode those components directly through
 * {@link ComponentSerialization#CODEC} and {@link NbtOps}: a plain literal is a {@link StringTag}, while styled or
 * structured text is normally a compound or list. Early two-sided-sign files instead put a JSON serialisation inside
 * each StringTag. Before that a sign stored {@code Text1}..{@code Text4} at the top level, also as JSON, with no back
 * side at all. Litematica files in circulation span all three shapes and this parser applies no datafixer, so all
 * three are read here; the legacy one answers only for the front.
 *
 * <h2>Why the game's own codec and not a substring</h2>
 *
 * <p>A message is a serialised {@link Component}, not a string. {@code {"text":"shulker","bold":true}} and
 * {@code ["a",{"text":"b"}]} are both legal and both mean something a naive unquote gets wrong. The rendered plain
 * text is what the sign editor's four text fields hold, so {@link Component#getString()} after a codec parse is the
 * only definition that matches what the executor will type — anything else types something the schematic did not ask
 * for and does it deterministically, on every sign, forever.
 *
 * <p>A message that will not parse yields {@link #UNREADABLE} rather than an empty line, and the caller reports the
 * cell instead of writing a blank sign over it. That distinction is the whole point: "this sign has no text" and "I
 * could not read this sign's text" are different answers and only one of them is safe to act on.
 */
public final class SignNbt {

    /** A sign side holds four lines. Vanilla's own constant is {@code SignText.LINES}; it is restated here so this
     *  class stays free of client types and can be unit-tested without a game instance. */
    public static final int LINES = BuildAction.WriteSign.MAX_LINES;

    /** Returned for a line whose component would not parse. Never typed into a sign — the caller turns it into a
     *  named limitation, because typing a diagnostic string onto a player's build is worse than leaving it blank. */
    public static final String UNREADABLE = "\u0000unreadable";

    private SignNbt() {
    }

    /**
     * Does this compound describe a sign at all?
     *
     * <p>Asked of the NBT rather than of the block state on purpose: the state says "this is an oak wall sign", the
     * NBT says whether the file carried anything to write on it. A sign cell with no compound is a blank sign the
     * schematic genuinely wants blank, and it must not produce a {@link BuildAction.WriteSign} — an action whose
     * confirmation is "the four lines are empty" would open the edit screen, type nothing and close it, spending a
     * screen round trip per sign to achieve the state that already existed.
     */
    public static boolean isSign(CompoundTag nbt) {
        if (nbt == null) {
            return false;
        }
        return nbt.getCompound("front_text").isPresent()
                || nbt.getCompound("back_text").isPresent()
                || nbt.getString("Text1").isPresent();
    }

    /**
     * The four lines of one side, or an empty list when that side carries nothing.
     *
     * <p>Trailing blank lines are kept, not trimmed: the sign editor's line index advances one field per confirm and
     * the executor types line by line, so a three-element list and a four-element list with a blank last line are the
     * same sign but not the same key sequence. Keeping the length fixed at four removes the question.
     *
     * @param frontSide 26.1.2 signs have two writable sides and they are separate NBT compounds
     * @return exactly {@link #LINES} strings, or an empty list when the side is absent or entirely blank
     */
    public static List<String> lines(CompoundTag nbt, boolean frontSide) {
        if (nbt == null) {
            return List.of();
        }
        CompoundTag side = nbt.getCompound(frontSide ? "front_text" : "back_text").orElse(null);
        List<String> decoded = side != null ? modernLines(side) : (frontSide ? legacyLines(nbt) : List.of());
        // A side whose four lines are all blank is a blank sign, and a blank sign needs no action. Answering with an
        // empty list rather than four empty strings lets the caller express that as "no WriteSign" instead of having
        // to re-derive it.
        for (String line : decoded) {
            if (!line.isEmpty()) {
                return decoded;
            }
        }
        return List.of();
    }

    /** Did any line of this side fail to parse? The caller reports the cell and skips it rather than writing a sign
     *  it cannot read. */
    public static boolean unreadable(List<String> lines) {
        return lines.contains(UNREADABLE);
    }

    /**
     * 1.20 and later: {@code front_text}/{@code back_text} → {@code messages} → four component tags.
     *
     * <p>Read the raw tag. {@link ListTag#getString(int)} is tempting and wrong: it turns every modern styled
     * component, whose element is a compound, into an empty Optional and therefore into a silently blank sign.
     */
    private static List<String> modernLines(CompoundTag side) {
        ListTag messages = side.getListOrEmpty("messages");
        if (messages.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(LINES);
        for (int i = 0; i < LINES; i++) {
            // A side with fewer than four messages is legal and means the remainder is blank.
            out.add(i < messages.size() ? plainText(messages.get(i)) : "");
        }
        return List.copyOf(out);
    }

    /** Before 1.20: {@code Text1}..{@code Text4} at the top level of the block entity, front side only. */
    private static List<String> legacyLines(CompoundTag nbt) {
        List<String> out = new ArrayList<>(LINES);
        for (int i = 1; i <= LINES; i++) {
            out.add(plainTextJson(nbt.getString("Text" + i).orElse("")));
        }
        return List.copyOf(out);
    }

    /**
     * One modern component tag as the plain text a sign renders.
     *
     * <p>Current NBT goes straight through the game's component codec. A StringTag that visibly starts like JSON is
     * the transitional 1.20 representation and takes the old JsonOps path instead. The two paths cannot simply be
     * tried in the opposite order: {@code ComponentSerialization.CODEC} quite correctly accepts every StringTag as a
     * literal, so the old value {@code "{\"text\":\"hello\"}"} would otherwise be typed including its braces.
     */
    private static String plainText(Tag encoded) {
        if (encoded == null) {
            return "";
        }
        if (encoded instanceof StringTag string) {
            String value = string.value();
            if (looksLikeJsonComponent(value)) {
                return plainTextJson(value);
            }
        }
        try {
            Component component = ComponentSerialization.CODEC
                    .parse(NbtOps.INSTANCE, encoded)
                    .result()
                    .orElse(null);
            return component == null ? UNREADABLE : component.getString();
        } catch (RuntimeException malformed) {
            return UNREADABLE;
        }
    }

    /**
     * Does a StringTag carry the old JSON-inside-NBT representation?
     *
     * <p>Legacy component JSON always begins with a quote, object or array after whitespace. A modern direct literal
     * normally does not. A literal that intentionally begins with one of those characters is inherently ambiguous
     * without a DataVersion on the individual block entity; preferring the historical representation preserves the
     * files this fallback exists for and makes malformed old JSON unreadable rather than typing its source verbatim.
     */
    private static boolean looksLikeJsonComponent(String value) {
        String trimmed = value == null ? "" : value.trim();
        return !trimmed.isEmpty()
                && (trimmed.charAt(0) == '"' || trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[');
    }

    /**
     * One legacy JSON-serialised component as the plain text a sign renders — which is the text the edit screen's
     * field holds and therefore the text the executor has to type.
     *
     * <p>An empty or absent message is an empty line, not an error: vanilla writes {@code '""'} for a blank line and
     * some tools write nothing at all.
     */
    private static String plainTextJson(String json) {
        String trimmed = json.trim();
        if (trimmed.isEmpty() || "\"\"".equals(trimmed)) {
            return "";
        }
        try {
            Component component = ComponentSerialization.CODEC
                    .parse(JsonOps.INSTANCE, JsonParser.parseString(trimmed))
                    .result()
                    .orElse(null);
            // A parse that fails softly returns an empty Optional rather than throwing; treating that as "blank" is
            // the silent-blank-sign failure this class exists to avoid, so it is a refusal like any other.
            return component == null ? UNREADABLE : component.getString();
        } catch (RuntimeException malformed) {
            // JsonParser throws on malformed input, and the codec can throw on a structurally valid but nonsensical
            // component. Neither is a reason to abandon the build; both are a reason not to type this line.
            return UNREADABLE;
        }
    }
}
