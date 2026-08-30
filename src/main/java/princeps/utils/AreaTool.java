package princeps.utils;

import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * The one tool the bot must never pick up by itself.
 *
 * <p>An area pickaxe -- the server-side "Shard Pickaxe" and its kin -- turns a single click into a 3x3 plane of
 * broken blocks. That is exactly what the excavation wants and exactly what WALKING must never have in hand, and
 * the difference is not a preference: automatic tool selection compares tools by mining SPEED, the two pickaxes
 * are the same diamond with the same efficiency, and a tie is won by the lower hotbar slot. So every block broken
 * to open a path was broken nine at a time -- including the block the bot had just decided to stand on.
 *
 * <p>Measured on the buried 30-cube, 19.08.: the snake correctly asked for the top band at y=-33, pathing found a
 * route to it every single second ("reached goal after 30000 nodes"), and the bot never arrived. It stayed on the
 * height it broke in at and wandered, because each step it mined toward the stance removed the floor of that step
 * along with the wall. The owner spotted it by watching: it IS trying to climb, it just keeps demolishing the
 * staircase. Nothing about the snake was wrong; the hand was.
 *
 * <p>So the rule lives here rather than in the builder: ANY automatic choice skips this tool, and only a
 * deliberate one -- the snake selecting it for a full 3x3 slice -- may hold it.
 */
public final class AreaTool {

    private static volatile String name = "shardpickaxe";

    private AreaTool() {
    }

    /** Sets the display name that identifies the area tool. Empty or blank names are ignored. */
    public static void setName(String displayName) {
        String normalised = normalise(displayName);
        if (!normalised.isEmpty()) {
            name = normalised;
        }
    }

    /** Whether this stack is the area tool. */
    public static boolean is(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String wanted = name;
        return !wanted.isEmpty() && normalise(stack.getHoverName().getString()).contains(wanted);
    }

    /**
     * Letters and digits only, lower-cased.
     *
     * <p>Names arrive with colour codes, italics markers and stray quotes depending on who wrote them -- the
     * server property carries the shell's closing quote, for one -- and none of that should decide whether the
     * bot recognises its own pickaxe.
     */
    public static String normalise(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(out::appendCodePoint);
        return out.toString();
    }
}
