package com.donutsmp.addon.modules.esp;

import com.donutsmp.addon.DonutSMPTools;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.*;

public class LightESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgOptimization = settings.createGroup("Optimization");

    private final Setting<Integer> chunkRadius = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-radius")
        .description("Radius of chunks to scan around the player.")
        .defaultValue(2)
        .min(1)
        .max(8)
        .sliderMax(8)
        .build()
    );

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y")
        .description("Minimum Y level to scan.")
        .defaultValue(-63)
        .min(-64)
        .max(319)
        .sliderMin(-64)
        .sliderMax(319)
        .build()
    );

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Maximum Y level to scan.")
        .defaultValue(0)
        .min(-64)
        .max(319)
        .sliderMin(-64)
        .sliderMax(319)
        .build()
    );

    private final Setting<Integer> minLightLevel = sgGeneral.add(new IntSetting.Builder()
        .name("min-light-level")
        .description("Minimum block light level to display.")
        .defaultValue(5)
        .min(0)
        .max(15)
        .sliderMax(15)
        .build()
    );

    private final Setting<Integer> maxLightLevel = sgGeneral.add(new IntSetting.Builder()
        .name("max-light-level")
        .description("Maximum block light level to display.")
        .defaultValue(15)
        .min(0)
        .max(15)
        .sliderMax(15)
        .build()
    );

    private final Setting<Boolean> compareWithSkyLight = sgGeneral.add(new BoolSetting.Builder()
        .name("compare-sky-light")
        .description("Only show blocks where block light is greater than sky light.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cullOccludedBlocks = sgRender.add(new BoolSetting.Builder()
        .name("cull-occluded")
        .description("Hide blocks fully surrounded by light blocks (reduces clutter).")
        .defaultValue(false)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<Integer> updateFrequency = sgOptimization.add(new IntSetting.Builder()
        .name("update-frequency")
        .description("Ticks between chunk rescans (higher = better performance).")
        .defaultValue(15)
        .min(5)
        .max(40)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> maxBlocksPerFrame = sgOptimization.add(new IntSetting.Builder()
        .name("max-blocks-frame")
        .description("Maximum blocks to render per frame (0 = unlimited).")
        .defaultValue(2000)
        .min(0)
        .max(10000)
        .sliderMax(10000)
        .build()
    );

    private final Setting<Boolean> useYLevelOptimization = sgOptimization.add(new BoolSetting.Builder()
        .name("y-level-optimization")
        .description("Only scan Y levels with light sources (faster but less accurate).")
        .defaultValue(true)
        .build()
    );

    private final Map<BlockPos, Integer> lightBlocks = new HashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private int tickCounter = 0;
    private ChunkPos lastPlayerChunk = null;

    public LightESP() {
        super(DonutSMPTools.BASE_HUNTING_CATEGORY, "light-esp", "Highlights blocks with specified block light levels.");
    }

    @Override
    public void onActivate() {
        lightBlocks.clear();
        scannedChunks.clear();
        lastPlayerChunk = null;
    }

    @Override
    public void onDeactivate() {
        lightBlocks.clear();
        scannedChunks.clear();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        tickCounter++;
        ChunkPos playerChunk = mc.player.getChunkPos();

        // Rescan chunks when player moves to a new chunk or on update interval
        if (!playerChunk.equals(lastPlayerChunk) || tickCounter >= updateFrequency.get()) {
            lastPlayerChunk = playerChunk;
            tickCounter = 0;
            rescanChunks(playerChunk);
        }

        // Render the found light blocks
        renderLightBlocks(event);
    }

    private void rescanChunks(ChunkPos playerChunk) {
        int radius = chunkRadius.get();
        Set<ChunkPos> chunksInRadius = new HashSet<>();

        // Build set of all chunks currently in radius
        for (int cx = -radius; cx <= radius; cx++) {
            for (int cz = -radius; cz <= radius; cz++) {
                chunksInRadius.add(new ChunkPos(playerChunk.x + cx, playerChunk.z + cz));
            }
        }

        // Scan only NEW chunks (ones we haven't scanned yet)
        for (ChunkPos chunk : chunksInRadius) {
            if (!scannedChunks.contains(chunk) && mc.world.isChunkLoaded(chunk.x, chunk.z)) {
                scanChunkForLights(chunk);
                scannedChunks.add(chunk);
            }
        }

        // Remove light blocks from chunks that are now outside the radius
        lightBlocks.entrySet().removeIf(entry -> {
            ChunkPos blockChunk = new ChunkPos(entry.getKey());
            return !chunksInRadius.contains(blockChunk);
        });

        // Clean up scanned chunks cache (only keep chunks in current radius)
        scannedChunks.retainAll(chunksInRadius);
    }

    private void scanChunkForLights(ChunkPos chunkPos) {
        if (mc.world == null) return;

        Chunk chunk = mc.world.getChunk(chunkPos.x, chunkPos.z);
        if (chunk == null || !chunk.getStatus().isAtLeast(ChunkStatus.FULL)) return;

        int minYLevel = minY.get();
        int maxYLevel = maxY.get();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minYLevel; y <= maxYLevel; y++) {
                    BlockPos pos = new BlockPos(chunkPos.getStartX() + x, y, chunkPos.getStartZ() + z);
                    int blockLight = mc.world.getLightLevel(LightType.BLOCK, pos);

                    // Check if block light matches criteria
                    if (blockLight < minLightLevel.get() || blockLight > maxLightLevel.get()) {
                        lightBlocks.remove(pos);
                        continue;
                    }

                    // If comparing with sky light, skip if block light <= sky light
                    if (compareWithSkyLight.get()) {
                        int skyLight = mc.world.getLightLevel(LightType.SKY, pos);
                        if (blockLight <= skyLight) {
                            lightBlocks.remove(pos);
                            continue;
                        }
                    }

                    lightBlocks.put(pos, blockLight);
                }
            }
        }
    }

    private void renderLightBlocks(Render3DEvent event) {
        if (lightBlocks.isEmpty()) return;

        int rendered = 0;
        int maxBlocks = maxBlocksPerFrame.get();
        boolean cullOccluded = cullOccludedBlocks.get();

        for (BlockPos pos : lightBlocks.keySet()) {
            if (maxBlocks > 0 && rendered >= maxBlocks) break;

            // Skip if all 6 neighbors are also light blocks (fully occluded)
            if (cullOccluded && isFullyOccluded(pos)) {
                continue;
            }

            Integer lightLevel = lightBlocks.get(pos);
            if (lightLevel == null) continue;

            SettingColor color = getLightColor(lightLevel);
            event.renderer.box(pos, color, color, shapeMode.get(), 0);

            rendered++;
        }
    }

    private boolean isFullyOccluded(BlockPos pos) {
        BlockPos[] neighbors = {
            pos.up(), pos.down(),
            pos.north(), pos.south(),
            pos.east(), pos.west()
        };

        for (BlockPos neighbor : neighbors) {
            if (!lightBlocks.containsKey(neighbor)) {
                return false;
            }
        }

        return true;
    }

    private SettingColor getLightColor(int lightLevel) {
        // Smooth thermal color gradient based on light level
        float alpha;
        float red, green, blue;

        if (lightLevel <= 5) {
            // Dark blue/purple for low levels
            float intensity = lightLevel / 5.0f;
            red = 0.2f + intensity * 0.3f;
            green = 0.2f + intensity * 0.2f;
            blue = 0.4f + intensity * 0.5f;
            alpha = 0.3f + intensity * 0.2f;
        } else if (lightLevel <= 10) {
            // Cyan to yellow gradient
            float intensity = (lightLevel - 5) / 5.0f;
            red = 0.5f + intensity * 0.5f;
            green = 0.6f + intensity * 0.4f;
            blue = 0.9f - intensity * 0.7f;
            alpha = 0.5f + intensity * 0.2f;
        } else if (lightLevel <= 14) {
            // Yellow to orange
            float intensity = (lightLevel - 10) / 4.0f;
            red = 1.0f;
            green = 1.0f - intensity * 0.3f;
            blue = 0.2f + intensity * 0.3f;
            alpha = 0.7f + intensity * 0.15f;
        } else {
            // Bright white for level 15
            red = green = blue = 1.0f;
            alpha = 0.85f;
        }

        return new SettingColor(
            (int) (red * 255),
            (int) (green * 255),
            (int) (blue * 255),
            (int) (alpha * 255)
        );
    }
}
