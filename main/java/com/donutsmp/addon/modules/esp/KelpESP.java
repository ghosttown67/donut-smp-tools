package com.donutsmp.addon.modules.esp;

import com.donutsmp.addon.DonutSMPTools;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.KelpBlock;
import net.minecraft.block.KelpPlantBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;

public class KelpESP extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgDetection = settings.createGroup("Detection");

    private final Setting<SettingColor> kelpColor = sgGeneral.add(new ColorSetting.Builder()
        .name("esp-color")
        .description("Kelp ESP box color")
        .defaultValue(new SettingColor(0, 255, 0, 100))
        .build());

    private final Setting<ShapeMode> kelpShapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Kelp ESP box render mode")
        .defaultValue(ShapeMode.Lines)
        .build());

    private final Setting<Boolean> kelpChat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce flagged kelp chunks in chat")
        .defaultValue(true)
        .build());

    private final Setting<DetectionMode> detectionMode = sgDetection.add(new EnumSetting.Builder<DetectionMode>()
        .name("Detection Mode")
        .description("How strict to be with kelp detection")
        .defaultValue(DetectionMode.STANDARD)
        .build());

    private final Setting<Integer> minKelpColumns = sgDetection.add(new IntSetting.Builder()
        .name("Min Kelp Columns")
        .description("Minimum kelp columns in chunk to flag")
        .defaultValue(10)
        .min(1)
        .max(256)
        .sliderRange(1, 100)
        .build());

    private final Setting<Integer> minKelpLength = sgDetection.add(new IntSetting.Builder()
        .name("Min Kelp Length")
        .description("Minimum kelp column height to count")
        .defaultValue(8)
        .min(1)
        .max(50)
        .sliderRange(1, 30)
        .build());

    private final Setting<Integer> targetY = sgDetection.add(new IntSetting.Builder()
        .name("Target Y Level")
        .description("Y level to look for kelp tops at")
        .defaultValue(62)
        .min(0)
        .max(256)
        .sliderRange(0, 256)
        .build());

    private final Setting<Double> topPercentage = sgDetection.add(new DoubleSetting.Builder()
        .name("Top Percentage")
        .description("Percentage of kelp that must be topped (0-100)")
        .defaultValue(60.0)
        .min(0.0)
        .max(100.0)
        .sliderRange(0.0, 100.0)
        .visible(() -> detectionMode.get() == DetectionMode.STANDARD)
        .build());

    private final Set<ChunkPos> flaggedKelpChunks = new HashSet<>();
    private final Set<ChunkPos> reportedChunks = new HashSet<>();

    public KelpESP() {
        super(DonutSMPTools.BASE_HUNTING_CATEGORY, "KelpESP", "ESP for kelp chunks with suspicious patterns.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null) return;
        flaggedKelpChunks.clear();
        reportedChunks.clear();

        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk worldChunk) scanChunkForKelp(worldChunk);
        }
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        scanChunkForKelp(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;

        Chunk chunk = mc.world.getChunk(pos);
        if (chunk instanceof WorldChunk worldChunk) {
            scanChunkForKelp(worldChunk);
        }
    }

    private void scanChunkForKelp(WorldChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        flaggedKelpChunks.remove(cpos);

        int xStart = cpos.getStartX();
        int zStart = cpos.getStartZ();
        int yMin = chunk.getBottomY();
        int yMax = yMin + chunk.getHeight();

        int kelpColumns = 0;
        int kelpTopsAtTarget = 0;

        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                int bottom = -1;
                int top = -1;

                for (int y = yMin; y < yMax; y++) {
                    Block block = chunk.getBlockState(new BlockPos(x, y, z)).getBlock();
                    if (block instanceof KelpBlock || block instanceof KelpPlantBlock) {
                        if (bottom < 0) bottom = y;
                        top = y;
                    }
                }

                if (bottom >= 0 && top - bottom + 1 >= minKelpLength.get()) {
                    kelpColumns++;
                    if (top == targetY.get()) kelpTopsAtTarget++;
                }
            }
        }

        boolean shouldFlag = false;

        if (kelpColumns >= minKelpColumns.get()) {
            if (detectionMode.get() == DetectionMode.ALL_TOPPED) {
                shouldFlag = kelpTopsAtTarget == kelpColumns && kelpColumns > 0;
            } else {
                double percentage = kelpColumns > 0 ? ((double) kelpTopsAtTarget / kelpColumns) * 100.0 : 0;
                shouldFlag = percentage >= topPercentage.get();
            }
        }

        if (shouldFlag) {
            flaggedKelpChunks.add(cpos);
            if (kelpChat.get() && !reportedChunks.contains(cpos)) {
                reportedChunks.add(cpos);
                String mode = detectionMode.get() == DetectionMode.ALL_TOPPED ? "ALL_TOPPED" : "STANDARD";
                info("KelpESP [" + mode + "]: Chunk " + cpos + " flagged: " + kelpTopsAtTarget + "/" + kelpColumns + " kelp tops at Y=" + targetY.get());
            }
        } else {
            flaggedKelpChunks.remove(cpos);
            reportedChunks.remove(cpos);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        Color side = new Color(kelpColor.get());
        Color outline = new Color(kelpColor.get());
        for (ChunkPos pos : flaggedKelpChunks) {
            event.renderer.box(
                pos.getStartX(), targetY.get(), pos.getStartZ(),
                pos.getStartX() + 16, targetY.get(), pos.getStartZ() + 16,
                side, outline, kelpShapeMode.get(), 0);
        }
    }

    public enum DetectionMode {
        STANDARD("Standard - Percentage Based"),
        ALL_TOPPED("All Topped - 100% Required");

        private final String label;

        DetectionMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
