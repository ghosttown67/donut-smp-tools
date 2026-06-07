package com.donutsmp.addon.modules.esp;

import com.donutsmp.addon.DonutSMPTools;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.*;
import java.util.concurrent.*;

public class CoveredHole extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");


    private final Setting<Boolean> chatNotifications = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notifications")
        .description("Send chat messages when covered holes are found")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyPlayerCovered = sgGeneral.add(new BoolSetting.Builder()
        .name("only-player-covered")
        .description("Only detect holes that appear to be intentionally covered")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxThreads = sgGeneral.add(new IntSetting.Builder()
        .name("max-threads")
        .description("Maximum number of threads to use for scanning")
        .defaultValue(4)
        .min(1)
        .max(8)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The color of the lines for covered holes")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The color of the sides for covered holes")
        .defaultValue(new SettingColor(255, 165, 0, 50))
        .build()
    );


    private final Map<Box, CoveredHoleInfo> coveredHoles = new ConcurrentHashMap<>();
    private final Set<Box> processedHoles = ConcurrentHashMap.newKeySet();
    private final Set<Box> pendingProcessing = ConcurrentHashMap.newKeySet();
    private final Set<Box> notifiedHoles = ConcurrentHashMap.newKeySet();
    private ExecutorService executorService;
    private final List<Future<Map.Entry<Box, CoveredHoleInfo>>> pendingTasks = new ArrayList<>();

    private final Map<BlockPos, Boolean> solidBlockCache = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockState> blockStateCache = new ConcurrentHashMap<>();

    private HoleTunnelStairsESP holeESP;
    private int tickCounter = 0;
    private volatile boolean isScanning = false;
    private long lastCacheClear = 0;
    private long lastCheckTime = 0;
    private String currentWorld = "";

    public CoveredHole() {
        super(DonutSMPTools.BASE_HUNTING_CATEGORY, "covered-hole", "Detects covered holes from HoleTunnelStairsESP with performance optimization.");
    }

    @Override
    public void onActivate() {
        executorService = Executors.newFixedThreadPool(maxThreads.get());

        holeESP = Modules.get().get(HoleTunnelStairsESP.class);
        if (holeESP == null || !holeESP.isActive()) {
            if (chatNotifications.get()) error("HoleTunnelStairsESP must be active for CoveredHole to work!");
            toggle();
            return;
        }

        clearAllData();

        if (mc.world != null) {
            currentWorld = mc.world.getRegistryKey().getValue().toString();
        }
    }

    @Override
    public void onDeactivate() {
        shutdownExecutor();
        clearAllData();
    }

    private void clearAllData() {
        coveredHoles.clear();
        processedHoles.clear();
        pendingProcessing.clear();
        solidBlockCache.clear();
        blockStateCache.clear();
        pendingTasks.clear();
        notifiedHoles.clear();
        isScanning = false;
        tickCounter = 0;
        lastCacheClear = System.currentTimeMillis();
        lastCheckTime = 0;
    }

    private void shutdownExecutor() {
        if (executorService != null) {
            isScanning = false;

            for (Future<?> task : pendingTasks) {
                task.cancel(true);
            }
            pendingTasks.clear();

            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void checkWorldChange() {
        if (mc.world == null) {
            currentWorld = "";
            clearAllData();
            return;
        }

        String newWorld = mc.world.getRegistryKey().getValue().toString();
        if (!newWorld.equals(currentWorld)) {
            currentWorld = newWorld;
            clearAllData();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        checkWorldChange();

        if (mc.world == null || mc.player == null) return;

        if (holeESP == null || !holeESP.isActive()) {
            if (chatNotifications.get()) error("HoleTunnelStairsESP was disabled!");
            toggle();
            return;
        }

        tickCounter++;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCacheClear > 5000) {
            clearOldCacheEntries();
            lastCacheClear = currentTime;
        }

        if (currentTime - lastCheckTime > 5000) {
            startAsyncScan();
            lastCheckTime = currentTime;
        }

        processCompletedTasks();
    }

    private void clearOldCacheEntries() {
        if (solidBlockCache.size() > 1000) {
            solidBlockCache.clear();
        }
        if (blockStateCache.size() > 1000) {
            blockStateCache.clear();
        }
    }

    private void startAsyncScan() {
        Set<Box> holes = getHolesFromHoleESP();
        if (holes == null || holes.isEmpty()) return;

        isScanning = true;

        List<Box> newHoles = new ArrayList<>();
        for (Box hole : holes) {
            if (!processedHoles.contains(hole) && !pendingProcessing.contains(hole)) {
                newHoles.add(hole);
                pendingProcessing.add(hole);
            }
        }

        int maxConcurrentTasks = Math.min(newHoles.size(), maxThreads.get());
        for (int i = 0; i < maxConcurrentTasks; i++) {
            if (i < newHoles.size()) {
                Future<Map.Entry<Box, CoveredHoleInfo>> future =
                    executorService.submit(new HoleCheckTask(newHoles.get(i)));
                pendingTasks.add(future);
            }
        }

        coveredHoles.keySet().retainAll(holes);
        processedHoles.retainAll(holes);
        pendingProcessing.retainAll(holes);
    }

    private void processCompletedTasks() {
        Iterator<Future<Map.Entry<Box, CoveredHoleInfo>>> iterator = pendingTasks.iterator();
        int processedCount = 0;
        final int maxProcessPerTick = 3;

        while (iterator.hasNext() && processedCount < maxProcessPerTick) {
            Future<Map.Entry<Box, CoveredHoleInfo>> task = iterator.next();

            if (task.isDone()) {
                try {
                    Map.Entry<Box, CoveredHoleInfo> result = task.get(1, TimeUnit.MILLISECONDS);
                    if (result != null) {
                        coveredHoles.put(result.getKey(), result.getValue());
                        pendingProcessing.remove(result.getKey());

                        if (chatNotifications.get() && !notifiedHoles.contains(result.getKey())) {
                            Box hole = result.getKey();
                            BlockPos coverPos = result.getValue().coverPos;
                            int depth = (int) (hole.maxY - hole.minY);
                            info(String.format("Covered Hole found at %s (depth: %d)",
                                coverPos.toShortString(), depth));
                            notifiedHoles.add(result.getKey());
                        }
                    }
                } catch (Exception e) {

                } finally {
                    iterator.remove();
                    processedCount++;
                }
            }
        }

        if (pendingTasks.isEmpty()) {
            isScanning = false;
        }
    }

    private Set<Box> getHolesFromHoleESP() {
        try {
            return holeESP != null ? holeESP.getHoles() : Collections.emptySet();
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null) return;

        for (Map.Entry<Box, CoveredHoleInfo> entry : coveredHoles.entrySet()) {
            Box hole = entry.getKey();
            CoveredHoleInfo info = entry.getValue();

            try {
                event.renderer.box(
                    hole.minX, hole.minY, hole.minZ,
                    hole.maxX, hole.maxY, hole.maxZ,
                    sideColor.get(), lineColor.get(),
                    shapeMode.get(), 0
                );

                event.renderer.box(
                    info.coverPos.getX(), info.coverPos.getY(), info.coverPos.getZ(),
                    info.coverPos.getX() + 1, info.coverPos.getY() + 1, info.coverPos.getZ() + 1,
                    sideColor.get(), lineColor.get(),
                    shapeMode.get(), 0
                );
            } catch (Exception e) {

            }
        }
    }

    private class HoleCheckTask implements Callable<Map.Entry<Box, CoveredHoleInfo>> {
        private final Box hole;

        public HoleCheckTask(Box hole) {
            this.hole = hole;
        }

        @Override
        public Map.Entry<Box, CoveredHoleInfo> call() {
            try {
                if (mc.world == null) return null;

                BlockPos topPos = new BlockPos(
                    (int) hole.minX,
                    (int) hole.maxY,
                    (int) hole.minZ
                );

                if (isSolidBlockCached(topPos)) {
                    boolean isPlayerCovered = !onlyPlayerCovered.get() ||
                        isLikelyPlayerCovered(topPos, hole);

                    if (isPlayerCovered) {
                        return new AbstractMap.SimpleEntry<>(hole, new CoveredHoleInfo(topPos, hole));
                    }
                }

                return null;
            } catch (Exception e) {
                return null;
            }
        }

        private boolean isLikelyPlayerCovered(BlockPos coverPos, Box hole) {
            try {
                BlockState coverBlock = getBlockStateCached(coverPos);
                if (coverBlock == null) return false;

                if (isCommonBuildingBlock(coverBlock)) {
                    return true;
                }

                int matchingBlocks = 0;
                BlockPos[] checkPositions = {
                    coverPos.north(),
                    coverPos.south(),
                    coverPos.east(),
                    coverPos.west()
                };

                for (BlockPos pos : checkPositions) {
                    BlockState state = getBlockStateCached(pos);
                    if (state != null && state.getBlock() == coverBlock.getBlock()) {
                        matchingBlocks++;
                    }
                }

                return matchingBlocks < 2;
            } catch (Exception e) {
                return false;
            }
        }

        private boolean isCommonBuildingBlock(BlockState state) {
            if (state == null) return false;

            String blockName = state.getBlock().getTranslationKey().toLowerCase();
            return blockName.contains("cobblestone") ||
                blockName.contains("stone_brick") ||
                blockName.contains("plank") ||
                blockName.contains("log") ||
                blockName.contains("wool") ||
                blockName.contains("concrete") ||
                blockName.contains("terracotta") ||
                blockName.contains("glass");
        }

        private boolean isSolidBlockCached(BlockPos pos) {
            if (mc.world == null) return false;

            return solidBlockCache.computeIfAbsent(pos, p -> {
                try {
                    BlockState state = mc.world.getBlockState(p);
                    return state != null && state.isSolidBlock(mc.world, p);
                } catch (Exception e) {
                    return false;
                }
            });
        }

        private BlockState getBlockStateCached(BlockPos pos) {
            if (mc.world == null) return null;

            return blockStateCache.computeIfAbsent(pos, p -> {
                try {
                    return mc.world.getBlockState(p);
                } catch (Exception e) {
                    return null;
                }
            });
        }
    }

    private static class CoveredHoleInfo {
        public final BlockPos coverPos;
        public final Box holeBox;

        public CoveredHoleInfo(BlockPos coverPos, Box holeBox) {
            this.coverPos = coverPos;
            this.holeBox = holeBox;
        }
    }
}
