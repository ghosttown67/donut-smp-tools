package com.donutsmp.addon.modules.esp;

import com.donutsmp.addon.DonutSMPTools;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;

public class ChunkFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRange = settings.createGroup("Range");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgBlockHighlight = settings.createGroup("Block Highlighting");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");
    private final SettingGroup sgNotifications = settings.createGroup("Notifications");


    private final Setting<Boolean> ignorePlayerChunk = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-player-chunk")
        .description("Ignore chunks containing the player.")
        .defaultValue(true)
        .build()
    );


    private final Setting<Boolean> detectDeepslate = sgDetection.add(new BoolSetting.Builder()
        .name("detect-deepslate")
        .description("Detect exposed deepslate.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> deepslateThreshold = sgDetection.add(new IntSetting.Builder()
        .name("deepslate-threshold")
        .description("Minimum deepslate blocks to flag.")
        .defaultValue(3)
        .min(1)
        .max(50)
        .sliderRange(1, 50)
        .build()
    );

    private final Setting<Boolean> detectCobbledDeepslate = sgDetection.add(new BoolSetting.Builder()
        .name("detect-cobbled-deepslate")
        .description("Detect cobbled deepslate blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cobbledThreshold = sgDetection.add(new IntSetting.Builder()
        .name("cobbled-threshold")
        .description("Minimum cobbled deepslate to flag.")
        .defaultValue(1)
        .min(1)
        .max(15)
        .sliderRange(1, 15)
        .build()
    );

    private final Setting<Boolean> detectRotatedDeepslate = sgDetection.add(new BoolSetting.Builder()
        .name("detect-rotated-deepslate")
        .description("Detect rotated deepslate blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> rotatedThreshold = sgDetection.add(new IntSetting.Builder()
        .name("rotated-threshold")
        .description("Minimum rotated deepslate to flag.")
        .defaultValue(2)
        .min(1)
        .max(20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> ignoreExposed = sgDetection.add(new BoolSetting.Builder()
        .name("ignore-exposed")
        .description("Ignore blocks exposed to air or fluids (caves/water).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> detectItems = sgDetection.add(new BoolSetting.Builder()
        .name("check-items")
        .description("Filter chunks with too many items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxItems = sgDetection.add(new IntSetting.Builder()
        .name("max-items")
        .description("Maximum item entities allowed.")
        .defaultValue(5)
        .min(0)
        .max(100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Boolean> detectXP = sgDetection.add(new BoolSetting.Builder()
        .name("check-xp-orbs")
        .description("Filter chunks with too many XP orbs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxXP = sgDetection.add(new IntSetting.Builder()
        .name("max-xp-orbs")
        .description("Maximum XP orb entities allowed.")
        .defaultValue(3)
        .min(0)
        .max(100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Boolean> ignoreChunksWithHoles = sgDetection.add(new BoolSetting.Builder()
        .name("ignore-chunks-with-holes")
        .description("Ignore chunks if a hole is found within the specified distance.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> holeCheckDistance = sgDetection.add(new IntSetting.Builder()
        .name("hole-check-distance")
        .description("Distance (in blocks) to check for holes around flagged chunks.")
        .defaultValue(16)
        .min(8)
        .max(64)
        .sliderRange(8, 64)
        .visible(ignoreChunksWithHoles::get)
        .build()
    );


    private final Setting<Integer> minScanY = sgRange.add(new IntSetting.Builder()
        .name("min-y")
        .description("Minimum Y level. Deepslate naturally generates < Y 8. > 16 is likely player made.")
        .defaultValue(16)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<Integer> maxScanY = sgRange.add(new IntSetting.Builder()
        .name("max-y")
        .description("Maximum Y level for scanning.")
        .defaultValue(320)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<Double> renderY = sgRender.add(new DoubleSetting.Builder()
        .name("render-height")
        .description("Height to render chunk highlights.")
        .defaultValue(64.0)
        .range(-64.0, 320.0)
        .sliderRange(-64.0, 320.0)
        .build()
    );

    private final Setting<ShapeMode> renderMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("render-mode")
        .description("How to render highlighted chunks.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> chunkColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-color")
        .description("Color for suspicious chunks.")
        .defaultValue(new SettingColor(255, 215, 0, 120))
        .build()
    );

    private final Setting<Double> thickness = sgRender.add(new DoubleSetting.Builder()
        .name("thickness")
        .description("Thickness of highlight box.")
        .defaultValue(0.3)
        .range(0.1, 2.0)
        .sliderRange(0.1, 2.0)
        .build()
    );

    private final Setting<Boolean> drawTracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw lines to suspicious chunks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color for tracer lines.")
        .defaultValue(new SettingColor(255, 69, 0, 180))
        .visible(drawTracers::get)
        .build()
    );

    private final Setting<Boolean> highlightBlocks = sgBlockHighlight.add(new BoolSetting.Builder()
        .name("highlight-blocks")
        .description("Highlight individual suspicious blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> blockRenderMode = sgBlockHighlight.add(new EnumSetting.Builder<ShapeMode>()
        .name("block-render-mode")
        .description("How to render individual blocks.")
        .defaultValue(ShapeMode.Lines)
        .visible(highlightBlocks::get)
        .build()
    );

    private final Setting<SettingColor> deepslateBlockColor = sgBlockHighlight.add(new ColorSetting.Builder()
        .name("deepslate-color")
        .description("Color for deepslate blocks.")
        .defaultValue(new SettingColor(100, 100, 100, 200))
        .visible(highlightBlocks::get)
        .build()
    );

    private final Setting<SettingColor> cobbledBlockColor = sgBlockHighlight.add(new ColorSetting.Builder()
        .name("cobbled-color")
        .description("Color for cobbled deepslate blocks.")
        .defaultValue(new SettingColor(80, 80, 80, 200))
        .visible(highlightBlocks::get)
        .build()
    );

    private final Setting<SettingColor> rotatedBlockColor = sgBlockHighlight.add(new ColorSetting.Builder()
        .name("rotated-color")
        .description("Color for rotated deepslate blocks.")
        .defaultValue(new SettingColor(120, 0, 120, 200))
        .visible(highlightBlocks::get)
        .build()
    );

    private final Setting<Integer> threadCount = sgPerformance.add(new IntSetting.Builder()
        .name("thread-count")
        .description("Number of worker threads.")
        .defaultValue(2)
        .range(1, 8)
        .sliderRange(1, 8)
        .build()
    );


    private final Setting<Boolean> chatAlerts = sgNotifications.add(new BoolSetting.Builder()
        .name("chat-alerts")
        .description("Send chat notifications.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> soundAlerts = sgNotifications.add(new BoolSetting.Builder()
        .name("sound-alerts")
        .description("Play sound when suspicious chunks found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> bellChime = sgNotifications.add(new BoolSetting.Builder()
        .name("bell-chime")
        .description("Play a bell chime sound when suspicious chunks found.")
        .defaultValue(false)
        .build()
    );


    private final ConcurrentHashMap<Long, ChunkAnalysis> flaggedChunks = new ConcurrentHashMap<>();
    private final Long2LongMap scannedChunks = new Long2LongOpenHashMap();
    private final Long2LongMap chunkNotificationTimes = new Long2LongOpenHashMap();
    private final Queue<WorldChunk> scanQueue = new ConcurrentLinkedQueue<>();
    private ExecutorService scannerPool;

    private int tickCounter = 0;
    private long playerChunkLong = 0L;

    public ChunkFinder() {
        super(DonutSMPTools.BASE_HUNTING_CATEGORY, "chunk-finder", "Highly optimized chunk finder for scanning 32+ chunks instantly.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null) return;

        clearData();
        startThreadPool();
    }

    @Override
    public void onDeactivate() {
        if (scannerPool != null) {
            scannerPool.shutdownNow();
            scannerPool = null;
        }

        clearData();
    }

    private void clearData() {
        flaggedChunks.clear();
        scanQueue.clear();

        synchronized (scannedChunks) {
            scannedChunks.clear();
            chunkNotificationTimes.clear();
        }
    }

    private void startThreadPool() {
        scannerPool = Executors.newFixedThreadPool(threadCount.get(), r -> {
            Thread t = new Thread(r, "ChunkFinder-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        for (int i = 0; i < threadCount.get(); i++) {
            scannerPool.execute(this::processQueue);
        }
    }

    private void processQueue() {
        while (!Thread.currentThread().isInterrupted() && isActive()) {
            WorldChunk chunk = scanQueue.poll();

            if (chunk != null) {
                try {
                    analyzeChunk(chunk);
                } catch (Exception e) {

                }
            } else {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) {
            return;
        }

        playerChunkLong = mc.player.getChunkPos().toLong();
        tickCounter++;

        if (tickCounter % 20 == 0) {

            evaluateEntities();


            for (Chunk chunk : Utils.chunks()) {
                if (chunk instanceof WorldChunk wc) {
                    long posLong = wc.getPos().toLong();

                    synchronized (scannedChunks) {
                        if (!scannedChunks.containsKey(posLong)) {
                            scannedChunks.put(posLong, System.currentTimeMillis());
                            scanQueue.offer(wc);
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (event.chunk() instanceof WorldChunk wc) {
            long posLong = wc.getPos().toLong();

            synchronized (scannedChunks) {
                if (!scannedChunks.containsKey(posLong)) {
                    scannedChunks.put(posLong, System.currentTimeMillis());
                    scanQueue.offer(wc);
                }
            }
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (event.pos.getY() < minScanY.get() || event.pos.getY() > maxScanY.get()) {
            return;
        }

        Block b = event.newState.getBlock();
        if (b == Blocks.DEEPSLATE || b == Blocks.COBBLED_DEEPSLATE) {
            long chunkPos = ChunkPos.toLong(event.pos);

            synchronized (scannedChunks) {
                scannedChunks.remove(chunkPos);
            }
        }
    }

    private void analyzeChunk(WorldChunk chunk) {
        long chunkPosLong = chunk.getPos().toLong();
        int min = minScanY.get();
        int max = maxScanY.get();

        ChunkAnalysis analysis = new ChunkAnalysis(chunk.getPos());
        BlockPos.Mutable mutablePos = new BlockPos.Mutable();

        ChunkSection[] sections = chunk.getSectionArray();
        int bottomSectionCoord = chunk.getBottomY() >> 4;


        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionBottomY = (bottomSectionCoord + i) * 16;
            int sectionTopY = sectionBottomY + 15;


            if (sectionTopY < min || sectionBottomY > max) continue;

            PalettedContainer<BlockState> palette = section.getBlockStateContainer();
            if (!sectionHasSusBlocks(palette)) continue;


            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    int worldY = sectionBottomY + y;

                    if (worldY < min || worldY > max) continue;

                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();

                        if (!isSusBlock(block)) continue;

                        mutablePos.set(chunk.getPos().getStartX() + x, worldY, chunk.getPos().getStartZ() + z);
                        evaluateBlock(chunk, mutablePos, state, analysis);
                    }
                }
            }
        }



        finalizeChunkAnalysis(chunkPosLong, analysis);
    }

    private boolean sectionHasSusBlocks(PalettedContainer<BlockState> palette) {
        return palette.hasAny(state -> isSusBlock(state.getBlock()));
    }

    private boolean isSusBlock(Block block) {
        return block == Blocks.DEEPSLATE ||
               block == Blocks.COBBLED_DEEPSLATE ||
               block == Blocks.POLISHED_DEEPSLATE ||
               block == Blocks.DEEPSLATE_BRICKS ||
               block == Blocks.DEEPSLATE_TILES ||
               block == Blocks.CHISELED_DEEPSLATE;
    }

    private void evaluateBlock(WorldChunk chunk, BlockPos pos, BlockState state, ChunkAnalysis analysis) {
        boolean exposed = ignoreExposed.get() && isExposedLocal(chunk, pos);
        Block block = state.getBlock();

        if (detectDeepslate.get() && block == Blocks.DEEPSLATE) {
            if (!exposed) {
                analysis.deepslateCount++;
                analysis.blocks.add(new SuspiciousBlock(pos.toImmutable(), SuspiciousBlockType.DEEPSLATE));
            }
        } else if (detectCobbledDeepslate.get() && block == Blocks.COBBLED_DEEPSLATE) {
            if (!exposed) {
                analysis.cobbledDeepslateCount++;
                analysis.blocks.add(new SuspiciousBlock(pos.toImmutable(), SuspiciousBlockType.COBBLED_DEEPSLATE));
            }
        } else if (detectRotatedDeepslate.get() && isRotated(state)) {
            if (!exposed) {
                analysis.rotatedDeepslateCount++;
                analysis.blocks.add(new SuspiciousBlock(pos.toImmutable(), SuspiciousBlockType.ROTATED_DEEPSLATE));
            }
        }
    }

    private boolean isRotated(BlockState state) {
        return state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Direction.Axis.Y;
    }

    private boolean isExposedLocal(WorldChunk chunk, BlockPos pos) {
        int x = pos.getX() & 15;
        int z = pos.getZ() & 15;
        int y = pos.getY();

        int topY = chunk.getBottomY() + chunk.getHeight() - 1;

        if (x > 0 && isAirOrFluid(chunk.getBlockState(pos.west()))) return true;
        if (x < 15 && isAirOrFluid(chunk.getBlockState(pos.east()))) return true;

        if (z > 0 && isAirOrFluid(chunk.getBlockState(pos.north()))) return true;
        if (z < 15 && isAirOrFluid(chunk.getBlockState(pos.south()))) return true;

        if (y > chunk.getBottomY() && isAirOrFluid(chunk.getBlockState(pos.down()))) return true;
        if (y < topY && isAirOrFluid(chunk.getBlockState(pos.up()))) return true;

        return false;
    }

    private boolean isAirOrFluid(BlockState state) {
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    private boolean isHoleNearChunk(ChunkPos chunkPos, int maxDistance) {
        if (mc.world == null) {
            return false;
        }

        int centerX = chunkPos.getStartX() + 8;
        int centerZ = chunkPos.getStartZ() + 8;

        for (int x = centerX - maxDistance; x <= centerX + maxDistance; x++) {
            for (int z = centerZ - maxDistance; z <= centerZ + maxDistance; z++) {
                for (int y = Math.max(mc.world.getBottomY(), minScanY.get()); y <= Math.min(mc.world.getTopYInclusive(), maxScanY.get()); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isValidHoleSection(pos)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isValidHoleSection(BlockPos pos) {
        if (mc.world == null) {
            return false;
        }

        if (isPassableBlock(pos) &&
            !isPassableBlock(pos.north()) &&
            !isPassableBlock(pos.south()) &&
            !isPassableBlock(pos.east()) &&
            !isPassableBlock(pos.west())) {
            return true;
        }

        for (int offset = 0; offset < 3; offset++) {
            BlockPos start = pos.west(offset);
            if (isPassableBlock(start) &&
                isPassableBlock(start.east()) &&
                isPassableBlock(start.east(2)) &&
                !isPassableBlock(start.north()) &&
                !isPassableBlock(start.south()) &&
                !isPassableBlock(start.west()) &&
                !isPassableBlock(start.east(3)) &&
                !isPassableBlock(start.east().north()) &&
                !isPassableBlock(start.east().south()) &&
                !isPassableBlock(start.east(2).north()) &&
                !isPassableBlock(start.east(2).south())) {
                return true;
            }
        }

        for (int offset = 0; offset < 3; offset++) {
            BlockPos start = pos.north(offset);
            if (isPassableBlock(start) &&
                isPassableBlock(start.south()) &&
                isPassableBlock(start.south(2)) &&
                !isPassableBlock(start.east()) &&
                !isPassableBlock(start.west()) &&
                !isPassableBlock(start.north()) &&
                !isPassableBlock(start.south(3)) &&
                !isPassableBlock(start.south().east()) &&
                !isPassableBlock(start.south().west()) &&
                !isPassableBlock(start.south(2).east()) &&
                !isPassableBlock(start.south(2).west())) {
                return true;
            }
        }

        return false;
    }

    private boolean isPassableBlock(BlockPos pos) {
        if (mc.world == null) {
            return false;
        }

        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }

        return state.getCollisionShape(mc.world, pos).isEmpty();
    }

    private void checkColumnStructures(WorldChunk chunk, Block targetBlock, int minLen, int minY, int maxY, ChunkAnalysis analysis) {
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int currentLen = 0;

                for (int y = maxY; y >= minY; y--) {
                    pos.set(chunk.getPos().getStartX() + x, y, chunk.getPos().getStartZ() + z);
                    BlockState state = chunk.getBlockState(pos);

                    if (state.getBlock() == targetBlock || (targetBlock == Blocks.KELP && state.getBlock() == Blocks.KELP_PLANT)) {
                        currentLen++;

                        if (currentLen >= minLen) {
                            return;
                        }
                    } else {
                        currentLen = 0;
                    }
                }
            }
        }
    }

    private void finalizeChunkAnalysis(long posLong, ChunkAnalysis analysis) {
        if (ignorePlayerChunk.get() && posLong == playerChunkLong) {
            return;
        }

        boolean suspicious = false;
        StringBuilder reason = new StringBuilder();

        if (detectDeepslate.get() && analysis.deepslateCount >= deepslateThreshold.get()) {
            suspicious = true;
            reason.append("Deepslate ");
        }

        if (detectCobbledDeepslate.get() && analysis.cobbledDeepslateCount >= cobbledThreshold.get()) {
            suspicious = true;
            reason.append("Cobbled ");
        }

        if (detectRotatedDeepslate.get() && analysis.rotatedDeepslateCount >= rotatedThreshold.get()) {
            suspicious = true;
            reason.append("Rotated ");
        }

        if (suspicious && ignoreChunksWithHoles.get()) {
            if (isHoleNearChunk(analysis.pos, holeCheckDistance.get())) {
                return;
            }
        }

        if (suspicious) {
            flaggedChunks.put(posLong, analysis);
            long now = System.currentTimeMillis();

            synchronized (chunkNotificationTimes) {
                if (now - chunkNotificationTimes.getOrDefault(posLong, 0L) > 60000) {
                    chunkNotificationTimes.put(posLong, now);

                    if (chatAlerts.get()) {
                        info("Suspicious chunk detected at [%d, %d] - %s", analysis.pos.x, analysis.pos.z, reason.toString().trim());
                    }

                    if (soundAlerts.get()) {
                        mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f));
                    }

                    if (bellChime.get()) {
                        mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 1.0f));
                    }
                }
            }
        } else {
            flaggedChunks.remove(posLong);
        }
    }

    private void evaluateEntities() {
        if (mc.world == null) {
            return;
        }

        if (!detectItems.get() && !detectXP.get()) {
            return;
        }

        Long2ObjectMap<int[]> entityCounts = new Long2ObjectOpenHashMap<>();

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof ItemEntity) {
                entityCounts.computeIfAbsent(entity.getChunkPos().toLong(), k -> new int[2])[0]++;
            } else if (entity instanceof ExperienceOrbEntity) {
                entityCounts.computeIfAbsent(entity.getChunkPos().toLong(), k -> new int[2])[1]++;
            }
        }

        for (Long2ObjectMap.Entry<int[]> entry : entityCounts.long2ObjectEntrySet()) {
            int items = entry.getValue()[0];
            int xp = entry.getValue()[1];

            if ((detectItems.get() && items > maxItems.get()) || (detectXP.get() && xp > maxXP.get())) {
                flaggedChunks.remove(entry.getLongKey());
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (flaggedChunks.isEmpty() || mc.player == null) {
            return;
        }

        Color highlight = new Color(chunkColor.get());
        Color tracerCol = new Color(tracerColor.get());
        Vec3d eyePos = drawTracers.get() ? mc.player.getEyePos() : null;

        for (ChunkAnalysis analysis : flaggedChunks.values()) {

            int startX = analysis.pos.getStartX();
            int startZ = analysis.pos.getStartZ();

            double y = renderY.get();
            double h = thickness.get();

            Box box = new Box(startX, y, startZ, startX + 16, y + h, startZ + 16);
            event.renderer.box(box, highlight, highlight, renderMode.get(), 0);


            if (drawTracers.get() && eyePos != null) {
                event.renderer.line(eyePos.x, eyePos.y, eyePos.z, startX + 8, y + h / 2.0, startZ + 8, tracerCol);
            }


            if (highlightBlocks.get()) {
                for (SuspiciousBlock sb : analysis.blocks) {
                    Color bColor = getColor(sb.type);
                    event.renderer.box(new Box(sb.pos), bColor, bColor, blockRenderMode.get(), 0);
                }
            }
        }
    }

    private Color getColor(SuspiciousBlockType type) {
        return switch (type) {
            case DEEPSLATE -> new Color(deepslateBlockColor.get());
            case COBBLED_DEEPSLATE -> new Color(cobbledBlockColor.get());
            case ROTATED_DEEPSLATE -> new Color(rotatedBlockColor.get());
        };
    }

    @Override
    public String getInfoString() {
        return String.valueOf(flaggedChunks.size());
    }

    private static class ChunkAnalysis {
        final ChunkPos pos;
        int deepslateCount = 0;
        int cobbledDeepslateCount = 0;
        int rotatedDeepslateCount = 0;

        final List<SuspiciousBlock> blocks = new ArrayList<>();

        ChunkAnalysis(ChunkPos pos) {
            this.pos = pos;
        }
    }

    private static class SuspiciousBlock {
        final BlockPos pos;
        final SuspiciousBlockType type;

        SuspiciousBlock(BlockPos pos, SuspiciousBlockType type) {
            this.pos = pos;
            this.type = type;
        }
    }

    private enum SuspiciousBlockType {
        DEEPSLATE, COBBLED_DEEPSLATE, ROTATED_DEEPSLATE
    }
}
