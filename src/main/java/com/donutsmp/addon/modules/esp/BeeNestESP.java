package com.donutsmp.addon.modules.esp;

import com.donutsmp.addon.DonutSMPTools;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BeehiveBlock;
import net.minecraft.block.BlockState;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class BeeNestESP extends Module {
    // Setting Groups for better organization in the GUI
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgNotifs = settings.createGroup("Notifications");

    // --- General Settings ---
    private final Setting<Integer> minHoneyLevel = sgGeneral.add(new IntSetting.Builder()
        .name("min-honey-level")
        .description("The minimum honey level a bee nest must have to be displayed.")
        .defaultValue(1)
        .min(1)
        .max(5)
        .sliderRange(1, 5)
        .build()
    );

    // --- Notification Settings ---
    private final Setting<Boolean> sendNotifs = sgNotifs.add(new BoolSetting.Builder()
        .name("send-notifications")
        .description("Sends a chat message when a new bee nest is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> playSound = sgNotifs.add(new BoolSetting.Builder()
        .name("play-sound")
        .description("Plays a sound when a new bee nest is found.")
        .defaultValue(true)
        .build()
    );

    // --- Render Settings (Color for each level) ---
    private final Setting<SettingColor> level1Color = sgRender.add(new ColorSetting.Builder()
        .name("level-1-color").description("The color for nests with honey level 1.")
        .defaultValue(new SettingColor(255, 255, 204, 75)).build());
    private final Setting<SettingColor> level2Color = sgRender.add(new ColorSetting.Builder()
        .name("level-2-color").description("The color for nests with honey level 2.")
        .defaultValue(new SettingColor(255, 255, 153, 75)).build());
    private final Setting<SettingColor> level3Color = sgRender.add(new ColorSetting.Builder()
        .name("level-3-color").description("The color for nests with honey level 3.")
        .defaultValue(new SettingColor(255, 229, 102, 75)).build());
    private final Setting<SettingColor> level4Color = sgRender.add(new ColorSetting.Builder()
        .name("level-4-color").description("The color for nests with honey level 4.")
        .defaultValue(new SettingColor(255, 204, 51, 75)).build());
    private final Setting<SettingColor> level5Color = sgRender.add(new ColorSetting.Builder()
        .name("level-5-color").description("The color for nests that are full (level 5).")
        .defaultValue(new SettingColor(255, 178, 0, 85)).build());

    private final Map<BlockPos, Integer> beeNests = new HashMap<>();
    private final Set<BlockPos> notifiedNests = new HashSet<>();
    private final Map<ChunkPos, Integer> cachedChunkHighlights = new HashMap<>();
    private int tickCounter = 0;
    private static final int UPDATE_INTERVAL = 20;
    private static final int SEARCH_RADIUS = 32;

    public BeeNestESP() {
        super(DonutSMPTools.BASE_HUNTING_CATEGORY, "bee-nest-esp", "Highlights bee nests with colors based on honey level.");
    }

    @Override
    public void onActivate() {
        notifiedNests.clear();
    }

    @Override
    public void onDeactivate() {
        beeNests.clear();
        notifiedNests.clear();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        tickCounter++;
        if (tickCounter >= UPDATE_INTERVAL) {
            tickCounter = 0;
            findBeeNests();

            cachedChunkHighlights.clear();
            beeNests.forEach((pos, level) -> {
                ChunkPos chunkPos = new ChunkPos(pos);
                cachedChunkHighlights.merge(chunkPos, level, Integer::max);
            });
        }

        if (mc.world == null) return;

        cachedChunkHighlights.forEach((chunkPos, level) -> {
            SettingColor color = getColorForLevel(level);
            if (color != null) {
                double x1 = chunkPos.getStartX();
                double z1 = chunkPos.getStartZ();
                double x2 = chunkPos.getEndX() + 1;
                double z2 = chunkPos.getEndZ() + 1;

                double y = mc.player.getY();
                event.renderer.box(x1, y, z1, x2, y + 1, z2, color, color, ShapeMode.Both, 0);
            }
        });
    }

    private void findBeeNests() {
        if (mc.world == null || mc.player == null) return;

        BlockPos playerPos = mc.player.getBlockPos();
        Set<BlockPos> toRemove = new HashSet<>(beeNests.keySet());

        ChunkPos playerChunk = new ChunkPos(playerPos);
        int chunkRadius = SEARCH_RADIUS >> 4;

        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                ChunkPos currentChunk = new ChunkPos(playerChunk.x + cx, playerChunk.z + cz);

                if (Math.abs(currentChunk.getCenterX() - playerPos.getX()) > SEARCH_RADIUS ||
                    Math.abs(currentChunk.getCenterZ() - playerPos.getZ()) > SEARCH_RADIUS) {
                    continue;
                }

                if (!mc.world.isChunkLoaded(currentChunk.x, currentChunk.z)) continue;

                int minY = Math.max(mc.world.getBottomY(), playerPos.getY() - SEARCH_RADIUS);
                int maxY = Math.min(320, playerPos.getY() + SEARCH_RADIUS);

                for (int y = minY; y <= maxY; y++) {
                    for (int x = currentChunk.getStartX(); x <= currentChunk.getEndX(); x++) {
                        for (int z = currentChunk.getStartZ(); z <= currentChunk.getEndZ(); z++) {
                            BlockPos pos = new BlockPos(x, y, z);

                            if (pos.getSquaredDistance(playerPos) > SEARCH_RADIUS * SEARCH_RADIUS) continue;

                            toRemove.remove(pos);

                            if (beeNests.containsKey(pos)) continue;

                            BlockState blockState = mc.world.getBlockState(pos);
                            if (blockState.getBlock() instanceof BeehiveBlock) {
                                int honeyLevel = blockState.get(BeehiveBlock.HONEY_LEVEL);
                                if (honeyLevel >= minHoneyLevel.get()) {
                                    beeNests.put(pos, honeyLevel);
                                    if (notifiedNests.add(pos)) {
                                        notifyPlayer(pos, honeyLevel);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        toRemove.forEach(beeNests::remove);
    }

    private void notifyPlayer(BlockPos pos, int level) {
        if (sendNotifs.get()) {
            info("Found bee nest (Level: %d) at X: %d, Y: %d, Z: %d", level, pos.getX(), pos.getY(), pos.getZ());
        }

        if (playSound.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }

    private SettingColor getColorForLevel(int level) {
        switch (level) {
            case 1: return level1Color.get();
            case 2: return level2Color.get();
            case 3: return level3Color.get();
            case 4: return level4Color.get();
            case 5: return level5Color.get();
            default: return null;
        }
    }
}
