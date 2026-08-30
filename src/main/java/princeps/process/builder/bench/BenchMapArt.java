/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process.builder.bench;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Map-art scenarios kept outside the owner's protected generic bench files. */
final class BenchMapArt {

    private BenchMapArt() {}

    private record Swatch(Block block, int rgb) {
        int red() { return (rgb >> 16) & 0xFF; }
        int green() { return (rgb >> 8) & 0xFF; }
        int blue() { return rgb & 0xFF; }
    }

    private static final String[] PREFERRED = {
        "_wool", "_concrete", "_terracotta", "_planks", "stone", "cobblestone", "sandstone", "netherrack",
    };

    static BenchSchematics.Scenario synthetic(int size, int maxMaterials) {
        List<Swatch> palette = palette();
        int used = Math.max(2, Math.min(maxMaterials, palette.size()));
        Map<Long, BlockState> cells = new LinkedHashMap<>();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                Swatch swatch = palette.get(((x + z) / 3) % used);
                cells.put(BenchSchematics.Scenario.key(x, 0, z), swatch.block().defaultBlockState());
            }
        }
        return new BenchSchematics.Scenario("mapart" + size, cells, size, 1, size);
    }

    static BenchSchematics.Scenario fromImage(String path, int size, int maxMaterials, boolean dither) {
        try {
            BufferedImage source = ImageIO.read(new File(path));
            if (source == null) {
                throw new IllegalStateException("not a readable image: " + path);
            }
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            var graphics = scaled.createGraphics();
            try {
                graphics.drawImage(source.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null);
            } finally {
                graphics.dispose();
            }

            Swatch[][] pixels = match(scaled, palette(), dither);
            Map<Swatch, Integer> used = new HashMap<>();
            for (Swatch[] row : pixels) {
                for (Swatch swatch : row) {
                    used.merge(swatch, 1, Integer::sum);
                }
            }
            int floor = Math.max(4, (size * size) / 500);
            List<Map.Entry<Swatch, Integer>> viable = used.entrySet().stream()
                    .filter(entry -> entry.getValue() >= floor)
                    .toList();
            List<Map.Entry<Swatch, Integer>> candidates = viable.isEmpty()
                    ? List.copyOf(used.entrySet()) : viable;
            if (candidates.size() < used.size() || candidates.size() > maxMaterials) {
                List<Swatch> kept = candidates.stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .limit(maxMaterials)
                        .map(Map.Entry::getKey)
                        .toList();
                pixels = match(scaled, kept, dither);
            }

            Map<Long, BlockState> cells = new LinkedHashMap<>();
            BufferedImage preview = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    Swatch swatch = pixels[z][x];
                    cells.put(BenchSchematics.Scenario.key(x, 0, z), swatch.block().defaultBlockState());
                    preview.setRGB(x, z, swatch.rgb());
                }
            }
            File previewFile = new File("bench-out/mapart-preview.png");
            File parent = previewFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            ImageIO.write(preview, "png", previewFile);
            System.out.println("[BENCH] map art preview written to " + previewFile.getAbsolutePath());
            return new BenchSchematics.Scenario("mapart-image", cells, size, 1, size);
        } catch (Exception exception) {
            throw new IllegalStateException("map art image failed: " + exception.getMessage(), exception);
        }
    }

    private static boolean unusable(Block block, BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (block instanceof FallingBlock || block.asItem() == Items.AIR || state.getLightEmission() > 0
                || state.hasBlockEntity() || !state.getProperties().isEmpty()
                || !state.isSolidRender() || !state.blocksMotion()) {
            return true;
        }
        boolean precious = id.equals("diamond_block") || id.equals("emerald_block") || id.equals("gold_block")
                || id.equals("lapis_block") || id.equals("netherite_block") || id.equals("ancient_debris")
                || id.equals("raw_gold_block") || id.equals("raw_copper_block") || id.equals("raw_iron_block")
                || id.equals("iron_block") || id.equals("copper_block") || id.equals("amethyst_block");
        return precious || id.contains("infested") || id.contains("command") || id.contains("bedrock")
                || id.contains("spawner") || id.endsWith("_ore") || id.contains("shulker_box");
    }

    private static int rank(Block block) {
        String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
        for (int index = 0; index < PREFERRED.length; index++) {
            if (id.endsWith(PREFERRED[index]) || id.equals(PREFERRED[index])) {
                return index;
            }
        }
        return PREFERRED.length + id.length();
    }

    private static List<Swatch> palette() {
        Map<MapColor, Block> best = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (unusable(block, state)) {
                continue;
            }
            MapColor color;
            try {
                color = state.getMapColor(null, null);
            } catch (Throwable ignored) {
                continue;
            }
            if (color == MapColor.NONE) {
                continue;
            }
            Block current = best.get(color);
            if (current == null || rank(block) < rank(current)) {
                best.put(color, block);
            }
        }
        List<Swatch> result = new ArrayList<>();
        for (Map.Entry<MapColor, Block> entry : best.entrySet()) {
            int rgb = entry.getKey().calculateARGBColor(MapColor.Brightness.NORMAL) & 0xFFFFFF;
            result.add(new Swatch(entry.getValue(), rgb));
        }
        return result;
    }

    private static Swatch[][] match(BufferedImage image, List<Swatch> palette, boolean dither) {
        int width = image.getWidth();
        int height = image.getHeight();
        double[][] errorRed = new double[height][width];
        double[][] errorGreen = new double[height][width];
        double[][] errorBlue = new double[height][width];
        Swatch[][] result = new Swatch[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                double red = ((rgb >> 16) & 0xFF) + errorRed[y][x];
                double green = ((rgb >> 8) & 0xFF) + errorGreen[y][x];
                double blue = (rgb & 0xFF) + errorBlue[y][x];
                Swatch chosen = nearest(palette, clamp(red), clamp(green), clamp(blue));
                result[y][x] = chosen;
                if (!dither) {
                    continue;
                }
                double dr = red - chosen.red();
                double dg = green - chosen.green();
                double db = blue - chosen.blue();
                spread(errorRed, errorGreen, errorBlue, x + 1, y, dr, dg, db, 7.0 / 16.0);
                spread(errorRed, errorGreen, errorBlue, x - 1, y + 1, dr, dg, db, 3.0 / 16.0);
                spread(errorRed, errorGreen, errorBlue, x, y + 1, dr, dg, db, 5.0 / 16.0);
                spread(errorRed, errorGreen, errorBlue, x + 1, y + 1, dr, dg, db, 1.0 / 16.0);
            }
        }
        return result;
    }

    private static Swatch nearest(List<Swatch> palette, int red, int green, int blue) {
        Swatch best = palette.get(0);
        int bestDistance = Integer.MAX_VALUE;
        for (Swatch swatch : palette) {
            int dr = red - swatch.red();
            int dg = green - swatch.green();
            int db = blue - swatch.blue();
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                best = swatch;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int clamp(double value) {
        return (int) Math.max(0, Math.min(255, value));
    }

    private static void spread(double[][] red, double[][] green, double[][] blue, int x, int y,
                               double dr, double dg, double db, double factor) {
        if (y < 0 || y >= red.length || x < 0 || x >= red[0].length) {
            return;
        }
        red[y][x] += dr * factor;
        green[y][x] += dg * factor;
        blue[y][x] += db * factor;
    }
}
