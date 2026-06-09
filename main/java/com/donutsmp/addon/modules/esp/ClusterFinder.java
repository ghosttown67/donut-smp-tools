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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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

public class ClusterFinder extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> clusterColor = sgGeneral.add(new ColorSetting.Builder()
        .name("esp-color")
        .description("Amethyst cluster box color")
        .defaultValue(new SettingColor(147, 0, 211, 100))
        .build());

    private final Setting<ShapeMode> clusterShapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Amethyst cluster box render mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to amethyst clusters")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Amethyst cluster tracer color")
        .defaultValue(new SettingColor(147, 0, 211, 200))
        .visible(tracers::get)
        .build());

    private final Setting<Boolean> clusterChat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce amethyst clusters in chat")
        .defaultValue(true)
        .build());

    private final SettingGroup sgFiltering = settings.createGroup("Cluster Types");

    private final Setting<Boolean> includeSmallBuds = sgFiltering.add(new BoolSetting.Builder()
        .name("small-buds")
        .description("Include small amethyst buds")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> includeMediumBuds = sgFiltering.add(new BoolSetting.Builder()
        .name("medium-buds")
        .description("Include medium amethyst buds")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> includeLargeBuds = sgFiltering.add(new BoolSetting.Builder()
        .name("large-buds")
        .description("Include large amethyst buds")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> includeClusters = sgFiltering.add(new BoolSetting.Builder()
        .name("clusters")
        .description("Include amethyst clusters")
        .defaultValue(true)
        .build());

    private final SettingGroup sgRange = settings.createGroup("Range");

    private final Setting<Integer> minY = sgRange.add(new IntSetting.Builder()
        .name("min-y")
        .description("Minimum Y level to scan for amethyst clusters")
        .defaultValue(-64)
        .min(-64)
        .max(128)
        .sliderRange(-64, 128)
        .build());

    private final Setting<Integer> maxY = sgRange.add(new IntSetting.Builder()
        .name("max-y")
        .description("Maximum Y level to scan for amethyst clusters")
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

    private final Set<BlockPos> clusterPositions = ConcurrentHashMap.newKeySet();
    private ExecutorService threadPool;

    public ClusterFinder() {
        super(DonutSMPTools.BASE_HUNTING_CATEGORY, "ClusterFinder", "ESP for amethyst clusters and buds with threading and tracer support.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null) return;

        if (useThreading.get()) {
            threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
        }

        clusterPositions.clear();

        if (useThreading.get()) {
            for (Chunk chunk : Utils.chunks()) {
                if (chunk instanceof WorldChunk wc) {
                    threadPool.submit(() -> scanChunkForClusters(wc));
                }
            }
        } else {
            for (Chunk chunk : Utils.chunks()) {
                if (chunk instanceof WorldChunk wc) scanChunkForClusters(wc);
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdownNow();
        }
        clusterPositions.clear();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(() -> scanChunkForClusters(event.chunk()));
        } else {
            scanChunkForClusters(event.chunk());
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState state = event.newState;

        if (isAmethystCluster(state, pos.getY())) {
            clusterPositions.add(pos);
            if (clusterChat.get() && !limitChatSpam.get()) {
                info("Found " + getClusterTypeName(state) + " at " + pos);
            }
        } else {
            clusterPositions.remove(pos);
        }
    }

    private void scanChunkForClusters(WorldChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int xStart = cpos.getStartX();
        int zStart = cpos.getStartZ();
        int yMin = Math.max(chunk.getBottomY(), minY.get());
        int yMax = Math.min(chunk.getBottomY() + chunk.getHeight(), maxY.get());

        Set<BlockPos> foundClusters = new HashSet<>();
        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                for (int y = yMin; y < yMax; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);

                    if (isAmethystCluster(state, y)) {
                        clusterPositions.add(pos);
                        foundClusters.add(pos);
                    }
                }
            }
        }

        if (!foundClusters.isEmpty() && clusterChat.get()) {
            info("Found " + foundClusters.size() + " amethyst clusters in chunk " + cpos);
        }
    }

    private boolean isAmethystCluster(BlockState state, int y) {
        if (includeSmallBuds.get() && state.getBlock() == Blocks.SMALL_AMETHYST_BUD) return true;
        if (includeMediumBuds.get() && state.getBlock() == Blocks.MEDIUM_AMETHYST_BUD) return true;
        if (includeLargeBuds.get() && state.getBlock() == Blocks.LARGE_AMETHYST_BUD) return true;
        if (includeClusters.get() && state.getBlock() == Blocks.AMETHYST_CLUSTER) return true;
        return false;
    }

    private String getClusterTypeName(BlockState state) {
        return switch (state.getBlock().toString()) {
            case "minecraft.block.SmallAmethystBudBlock" -> "Small Bud";
            case "minecraft.block.MediumAmethystBudBlock" -> "Medium Bud";
            case "minecraft.block.LargeAmethystBudBlock" -> "Large Bud";
            case "minecraft.block.AmethystClusterBlock" -> "Cluster";
            default -> "Amethyst";
        };
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        Color side = new Color(clusterColor.get());
        Color outline = new Color(clusterColor.get());

        for (BlockPos pos : clusterPositions) {
            double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(pos));
            if (dist > 150) continue;

            event.renderer.box(new net.minecraft.util.math.Box(pos), side, outline, clusterShapeMode.get(), 0);

            if (tracers.get()) {
                Vec3d playerPos = mc.gameRenderer.getCamera().getPos();
                event.renderer.line(playerPos.x, playerPos.y, playerPos.z,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    new Color(tracerColor.get()));
            }
        }
    }
}
