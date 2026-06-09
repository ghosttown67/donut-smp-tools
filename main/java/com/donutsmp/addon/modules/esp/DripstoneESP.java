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
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DripstoneESP extends Module {
    private final SettingGroup sgStalactite = settings.createGroup("Stalactite ESP");

    private final Setting<SettingColor> stalactiteColor = sgStalactite.add(new ColorSetting.Builder()
        .name("esp-color")
        .description("Stalactite ESP box color")
        .defaultValue(new SettingColor(100, 255, 200, 100))
        .build());

    private final Setting<ShapeMode> stalactiteShapeMode = sgStalactite.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Stalactite box render mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Boolean> stalactiteTracers = sgStalactite.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to stalactites")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> stalactiteTracerColor = sgStalactite.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Stalactite tracer color")
        .defaultValue(new SettingColor(100, 255, 200, 200))
        .visible(stalactiteTracers::get)
        .build());

    private final Setting<Boolean> stalactiteChat = sgStalactite.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce long dripstone stalactites in chat")
        .defaultValue(true)
        .build());

    private final Setting<Integer> stalactiteMinLength = sgStalactite.add(new IntSetting.Builder()
        .name("min-length")
        .description("Minimum length for stalactite to show ESP")
        .defaultValue(4)
        .min(4)
        .max(16)
        .sliderRange(4, 16)
        .build());

    private final SettingGroup sgStalagmite = settings.createGroup("Stalagmite ESP");

    private final Setting<SettingColor> stalagmiteColor = sgStalagmite.add(new ColorSetting.Builder()
        .name("esp-color")
        .description("Stalagmite ESP box color")
        .defaultValue(new SettingColor(255, 150, 100, 100))
        .build());

    private final Setting<ShapeMode> stalagmiteShapeMode = sgStalagmite.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Stalagmite box render mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Boolean> stalagmiteTracers = sgStalagmite.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to stalagmites")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> stalagmiteTracerColor = sgStalagmite.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Stalagmite tracer color")
        .defaultValue(new SettingColor(255, 150, 100, 200))
        .visible(stalagmiteTracers::get)
        .build());

    private final Setting<Boolean> stalagmiteChat = sgStalagmite.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce long dripstone stalagmites in chat")
        .defaultValue(true)
        .build());

    private final Setting<Integer> stalagmiteMinLength = sgStalagmite.add(new IntSetting.Builder()
        .name("min-length")
        .description("Minimum length for stalagmite to show ESP")
        .defaultValue(8)
        .min(4)
        .max(16)
        .sliderRange(4, 16)
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

    private final Set<BlockPos> longStalactiteBottoms = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> longStalagmiteTops = ConcurrentHashMap.newKeySet();
    private ExecutorService threadPool;

    public DripstoneESP() {
        super(DonutSMPTools.BASE_HUNTING_CATEGORY, "DripstoneESP", "ESP for long dripstone stalactites and stalagmites with threading support.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null) return;

        if (useThreading.get()) {
            threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
        }

        longStalactiteBottoms.clear();
        longStalagmiteTops.clear();

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
        longStalactiteBottoms.clear();
        longStalagmiteTops.clear();
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

        if (isDripstone(state)) {
            checkDripstone(pos);
        }
    }

    private void scanChunk(WorldChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int xStart = cpos.getStartX();
        int zStart = cpos.getStartZ();
        int yMin = chunk.getBottomY();
        int yMax = yMin + chunk.getHeight();

        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                for (int y = yMin; y < yMax; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (isDripstone(state)) {
                        checkDripstone(pos);
                    }
                }
            }
        }
    }

    private void checkDripstone(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (!isDripstone(state)) return;

        if (!(state.getBlock() instanceof PointedDripstoneBlock)) return;

        boolean isPointingDown = state.get(PointedDripstoneBlock.VERTICAL_DIRECTION) == Direction.DOWN;

        if (isPointingDown) {
            int length = measureStalactiteLength(pos);
            if (length >= stalactiteMinLength.get()) {
                longStalactiteBottoms.add(pos);
                if (stalactiteChat.get()) {
                    info("Found stalactite of length " + length + " at " + pos);
                }
            }
        } else {
            int length = measureStalagmiteLength(pos);
            if (length >= stalagmiteMinLength.get()) {
                longStalagmiteTops.add(pos);
                if (stalagmiteChat.get()) {
                    info("Found stalagmite of length " + length + " at " + pos);
                }
            }
        }
    }

    private int measureStalactiteLength(BlockPos bottom) {
        int length = 0;
        BlockPos current = bottom;
        while (isDripstone(mc.world.getBlockState(current))) {
            length++;
            current = current.up();
            if (length > 16) break;
        }
        return length;
    }

    private int measureStalagmiteLength(BlockPos top) {
        int length = 0;
        BlockPos current = top;
        while (isDripstone(mc.world.getBlockState(current))) {
            length++;
            current = current.down();
            if (length > 16) break;
        }
        return length;
    }

    private boolean isDripstone(BlockState state) {
        return state.getBlock() == Blocks.POINTED_DRIPSTONE;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        Color stalactiteSide = new Color(stalactiteColor.get());
        Color stalactiteOutline = new Color(stalactiteColor.get());

        for (BlockPos pos : longStalactiteBottoms) {
            double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(pos));
            if (dist > 150) continue;

            event.renderer.box(new net.minecraft.util.math.Box(pos), stalactiteSide, stalactiteOutline, stalactiteShapeMode.get(), 0);

            if (stalactiteTracers.get()) {
                Vec3d playerPos = mc.gameRenderer.getCamera().getPos();
                event.renderer.line(playerPos.x, playerPos.y, playerPos.z,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    new Color(stalactiteTracerColor.get()));
            }
        }

        Color stalagmiteSide = new Color(stalagmiteColor.get());
        Color stalagmiteOutline = new Color(stalagmiteColor.get());

        for (BlockPos pos : longStalagmiteTops) {
            double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(pos));
            if (dist > 150) continue;

            event.renderer.box(new net.minecraft.util.math.Box(pos), stalagmiteSide, stalagmiteOutline, stalagmiteShapeMode.get(), 0);

            if (stalagmiteTracers.get()) {
                Vec3d playerPos = mc.gameRenderer.getCamera().getPos();
                event.renderer.line(playerPos.x, playerPos.y, playerPos.z,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    new Color(stalagmiteTracerColor.get()));
            }
        }
    }
}
