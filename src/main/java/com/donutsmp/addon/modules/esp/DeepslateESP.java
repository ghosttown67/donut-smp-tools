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
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeepslateESP extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> deepslateColor = sgGeneral.add(new ColorSetting.Builder()
        .name("esp-color")
        .description("Deepslate box color")
        .defaultValue(new SettingColor(0, 200, 255, 100))
        .build());

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Deepslate box render mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to deepslate blocks")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Deepslate tracer color")
        .defaultValue(new SettingColor(0, 200, 255, 200))
        .visible(tracers::get)
        .build());

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce deepslate in chat")
        .defaultValue(true)
        .build());

    private final SettingGroup sgFiltering = settings.createGroup("Filtering");

    private final Setting<Integer> minY = sgFiltering.add(new IntSetting.Builder()
        .name("min-y")
        .description("Minimum Y level to scan for deepslate")
        .defaultValue(8)
        .min(-64)
        .max(128)
        .sliderRange(-64, 128)
        .build());

    private final Setting<Integer> maxY = sgFiltering.add(new IntSetting.Builder()
        .name("max-y")
        .description("Maximum Y level to scan for deepslate")
        .defaultValue(128)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .build());

    private final SettingGroup sgThreading = settings.createGroup("Threading");

    private final Setting<Boolean> useThreading = sgThreading.add(new BoolSetting.Builder()
        .name("enable-threading")
        .description("Use multi-threading for chunk scanning (better performance)")
        .defaultValue(true)
        .build());

    private final Setting<Integer> threadPoolSize = sgThreading.add(new IntSetting.Builder()
        .name("thread-pool-size")
        .description("Number of threads to use for scanning")
        .defaultValue(2)
        .min(1)
        .max(8)
        .sliderRange(1, 8)
        .visible(useThreading::get)
        .build());

    private final Setting<Boolean> limitChatSpam = sgThreading.add(new BoolSetting.Builder()
        .name("limit-chat-spam")
        .description("Reduce chat spam when using threading")
        .defaultValue(true)
        .visible(useThreading::get)
        .build());

    private final Set<BlockPos> deepslatePositions = ConcurrentHashMap.newKeySet();
    private ExecutorService threadPool;

    public DeepslateESP() {
        super(DonutSMPTools.BASE_HUNTING_CATEGORY, "DeepslateESP", "ESP for deepslate blocks with threading and tracer support.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null) return;

        if (useThreading.get()) {
            threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
        }

        deepslatePositions.clear();

        if (useThreading.get()) {
            for (Chunk chunk : Utils.chunks()) {
                if (chunk instanceof WorldChunk wc) {
                    threadPool.submit(() -> scanChunk(wc));
                }
            }
        } else {
            for (Chunk chunk : Utils.chunks()) {
                if (chunk instanceof WorldChunk wc) scanChunk(wc);
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdownNow();
        }
        deepslatePositions.clear();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(() -> scanChunk(event.chunk()));
        } else {
            scanChunk(event.chunk());
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState state = event.newState;

        if (isDeepslateInRange(state, pos.getY())) {
            deepslatePositions.add(pos);
            if (chatFeedback.get()) {
                info("Found deepslate at " + pos);
            }
        } else {
            deepslatePositions.remove(pos);
        }
    }

    private void scanChunk(WorldChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int xStart = cpos.getStartX();
        int zStart = cpos.getStartZ();
        int yMin = Math.max(chunk.getBottomY(), minY.get());
        int yMax = Math.min(chunk.getBottomY() + chunk.getHeight(), maxY.get());

        Set<BlockPos> foundDeepslate = new HashSet<>();
        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                for (int y = yMin; y < yMax; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);

                    if (isDeepslateInRange(state, y)) {
                        deepslatePositions.add(pos);
                        foundDeepslate.add(pos);
                    }
                }
            }
        }

        if (!foundDeepslate.isEmpty() && chatFeedback.get()) {
            info("Found " + foundDeepslate.size() + " deepslate blocks in chunk " + cpos);
        }
    }

    private boolean isDeepslateInRange(BlockState state, int y) {
        return state.getBlock() == Blocks.DEEPSLATE && y >= minY.get() && y <= maxY.get();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        Color side = new Color(deepslateColor.get());
        Color outline = new Color(deepslateColor.get());

        for (BlockPos pos : deepslatePositions) {
            double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(pos));
            if (dist > 150) continue;

            event.renderer.box(new net.minecraft.util.math.Box(pos), side, outline, shapeMode.get(), 0);

            if (tracers.get()) {
                Vec3d playerPos = mc.gameRenderer.getCamera().getPos();
                event.renderer.line(playerPos.x, playerPos.y, playerPos.z,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    new Color(tracerColor.get()));
            }
        }
    }
}
