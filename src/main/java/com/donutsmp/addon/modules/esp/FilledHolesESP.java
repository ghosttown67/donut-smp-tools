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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FilledHolesESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");
    private final SettingGroup sgThreading = settings.createGroup("Threading");


    private final Setting<SettingColor> blockColor = sgGeneral.add(new ColorSetting.Builder()
        .name("block-color")
        .description("Color for detected filled hole blocks")
        .defaultValue(new SettingColor(255, 0, 0, 100))
        .build());

    private final Setting<SettingColor> chunkColor = sgGeneral.add(new ColorSetting.Builder()
        .name("chunk-color")
        .description("Color for chunk containing detections")
        .defaultValue(new SettingColor(255, 0, 0, 50))
        .build());

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Render mode for blocks")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Boolean> renderIndividualBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("render-individual-blocks")
        .description("Highlight individual detected blocks")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> renderChunkHighlight = sgGeneral.add(new BoolSetting.Builder()
        .name("render-chunk-highlight")
        .description("Highlight entire chunk containing detections")
        .defaultValue(false)
        .build());


    private final Setting<Integer> columnHeightThreshold = sgDetection.add(new IntSetting.Builder()
        .name("column-height-threshold")
        .description("Minimum vertical blocks to flag (1-100)")
        .defaultValue(4)
        .min(1)
        .max(100)
        .sliderRange(1, 100)
        .build());

    private final Setting<Integer> minYLevel = sgDetection.add(new IntSetting.Builder()
        .name("min-y-level")
        .description("Minimum Y-coordinate to search")
        .defaultValue(16)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .build());

    private final Setting<Integer> maxYLevel = sgDetection.add(new IntSetting.Builder()
        .name("max-y-level")
        .description("Maximum Y-coordinate to search")
        .defaultValue(48)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .build());

    private final Setting<Integer> minSideCoverage = sgDetection.add(new IntSetting.Builder()
        .name("min-covered-sides")
        .description("Minimum number of sides that must be covered (2-4)")
        .defaultValue(4)
        .min(2)
        .max(4)
        .sliderRange(2, 4)
        .build());

    private final Setting<java.util.List<Block>> fillerBlocks = sgDetection.add(new BlockListSetting.Builder()
        .name("filler-blocks")
        .description("Filler blocks to detect")
        .defaultValue(java.util.Arrays.asList(
            Blocks.GRANITE,
            Blocks.DIORITE,
            Blocks.ANDESITE,
            Blocks.TUFF,
            Blocks.GRAVEL
        ))
        .build());

    private final Setting<Boolean> requireVerticalAlignment = sgDetection.add(new BoolSetting.Builder()
        .name("require-vertical-alignment")
        .description("Require blocks above/below to also be filler (prevents natural veins)")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ignoreChunksWithHoles = sgDetection.add(new BoolSetting.Builder()
        .name("ignore-chunks-with-holes")
        .description("Ignore chunks if a hole is found within the specified distance.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> holeCheckDistance = sgDetection.add(new IntSetting.Builder()
        .name("hole-check-distance")
        .description("Distance (in blocks) to check for holes around detected chunks.")
        .defaultValue(16)
        .min(8)
        .max(64)
        .sliderRange(8, 64)
        .visible(ignoreChunksWithHoles::get)
        .build());


    private final Setting<Boolean> tracers = sgAdvanced.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to detected blocks")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> tracerColor = sgAdvanced.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Tracer line color")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .visible(tracers::get)
        .build());

    private final Setting<Double> tracerThickness = sgAdvanced.add(new DoubleSetting.Builder()
        .name("tracer-thickness")
        .description("Tracer line thickness")
        .defaultValue(2.0)
        .min(0.1)
        .max(5.0)
        .sliderRange(0.1, 5.0)
        .visible(tracers::get)
        .build());

    private final Setting<Boolean> chatNotifications = sgAdvanced.add(new BoolSetting.Builder()
        .name("chat-notifications")
        .description("Send chat messages when filled holes are found")
        .defaultValue(false)
        .build());


    private final Setting<Boolean> useThreading = sgThreading.add(new BoolSetting.Builder()
        .name("enable-threading")
        .description("Use multi-threading for chunk scanning")
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


    private final Setting<Boolean> debugLogging = sgAdvanced.add(new BoolSetting.Builder()
        .name("debug-logging")
        .description("Log detection info to chat (may be spammy)")
        .defaultValue(false)
        .build());



    private final Set<BlockPos> detectedBlocks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> detectedChunks = ConcurrentHashMap.newKeySet();
    private ExecutorService threadPool;

    public FilledHolesESP() {
        super(DonutSMPTools.BASE_HUNTING_CATEGORY, "filled-holes", "Highlights filled tunnels and holes using filler blocks.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null) return;

        if (useThreading.get()) {
            threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
        }

        detectedBlocks.clear();
        detectedChunks.clear();

        if (useThreading.get() && threadPool != null) {
            for (Chunk chunk : Utils.chunks()) {
                if (chunk instanceof WorldChunk worldChunk) {
                    threadPool.submit(() -> scanChunk(worldChunk));
                }
            }
        } else {
            for (Chunk chunk : Utils.chunks()) {
                if (chunk instanceof WorldChunk worldChunk) {
                    scanChunk(worldChunk);
                }
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            threadPool = null;
        }
        detectedBlocks.clear();
        detectedChunks.clear();
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

        Runnable updateTask = () -> {

            BlockPos checkBase = findColumnBase(pos);

            if (isFilledHole(checkBase)) {

                List<BlockPos> columnBlocks = getColumnBlocks(checkBase);
                for (BlockPos blockPos : columnBlocks) {
                    detectedBlocks.add(blockPos);
                }
                detectedChunks.add(new ChunkPos(checkBase));
            } else {


                BlockPos current = pos;
                Set<BlockPos> toRemove = new HashSet<>();


                for (int i = 0; i < columnHeightThreshold.get() * 2; i++) {
                    BlockPos checkPos = current.up(i);
                    ChunkPos checkChunk = new ChunkPos(checkPos);
                    if (detectedBlocks.contains(checkPos)) {
                        toRemove.add(checkPos);
                    }
                }

                for (int i = 0; i < columnHeightThreshold.get() * 2; i++) {
                    BlockPos checkPos = current.down(i);
                    ChunkPos checkChunk = new ChunkPos(checkPos);
                    if (detectedBlocks.contains(checkPos)) {
                        toRemove.add(checkPos);
                    }
                }


                for (BlockPos removePos : toRemove) {
                    if (!isFilledHole(removePos)) {
                        detectedBlocks.remove(removePos);
                    } else {

                        List<BlockPos> columnBlocks = getColumnBlocks(findColumnBase(removePos));
                        detectedBlocks.addAll(columnBlocks);
                    }
                }
            }


            for (ChunkPos cpos : new HashSet<>(detectedChunks)) {
                boolean hasBlocks = false;
                for (BlockPos bp : detectedBlocks) {
                    if (new ChunkPos(bp).equals(cpos)) {
                        hasBlocks = true;
                        break;
                    }
                }
                if (!hasBlocks) {
                    detectedChunks.remove(cpos);
                }
            }
        };

        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(updateTask);
        } else {
            updateTask.run();
        }
    }

    private void scanChunk(WorldChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int xStart = cpos.getStartX();
        int zStart = cpos.getStartZ();
        Set<BlockPos> chunkDetections = new HashSet<>();
        Set<BlockPos> processedBases = new HashSet<>();


        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                int consecutiveFiller = 0;
                int baseY = 0;

                for (int y = Math.max(chunk.getBottomY(), minYLevel.get()); y <= Math.min(chunk.getBottomY() + chunk.getHeight() - 1, maxYLevel.get()); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    Block block = state.getBlock();


                    if (isFillerBlock(block) && !isAirBlock(block)) {
                        if (consecutiveFiller == 0) {
                            baseY = y;
                        }
                        consecutiveFiller++;
                    } else {

                        if (consecutiveFiller >= columnHeightThreshold.get()) {
                            BlockPos columnBase = new BlockPos(x, baseY, z);

                            if (!processedBases.contains(columnBase)) {
                                processedBases.add(columnBase);


                                if (isColumnSurrounded(columnBase, consecutiveFiller)) {

                                    for (int i = 0; i < consecutiveFiller; i++) {
                                        chunkDetections.add(columnBase.up(i));
                                    }
                                }
                            }
                        }
                        consecutiveFiller = 0;
                    }
                }


                if (consecutiveFiller >= columnHeightThreshold.get()) {
                    BlockPos columnBase = new BlockPos(x, baseY, z);

                    if (!processedBases.contains(columnBase)) {
                        processedBases.add(columnBase);

                        if (isColumnSurrounded(columnBase, consecutiveFiller)) {
                            for (int i = 0; i < consecutiveFiller; i++) {
                                chunkDetections.add(columnBase.up(i));
                            }
                        }
                    }
                }
            }
        }


        detectedBlocks.removeIf(pos -> {
            ChunkPos blockChunk = new ChunkPos(pos);
            return blockChunk.equals(cpos) && !chunkDetections.contains(pos);
        });


        int newDetections = 0;
        for (BlockPos pos : chunkDetections) {
            if (detectedBlocks.add(pos)) {
                newDetections++;
            }
        }


        if (!chunkDetections.isEmpty()) {
            detectedChunks.add(cpos);
        } else {
            detectedChunks.remove(cpos);
        }


        if (ignoreChunksWithHoles.get() && isHoleNearChunk(cpos, holeCheckDistance.get())) {

            return;
        }


        if (chatNotifications.get() && !chunkDetections.isEmpty()) {
            String blockType = "Unknown";
            if (!chunkDetections.isEmpty()) {
                BlockPos sample = chunkDetections.iterator().next();
                Block block = mc.world.getBlockState(sample).getBlock();
                String blockName = Registries.BLOCK.getId(block).getPath();

                String[] parts = blockName.split("_");
                StringBuilder titleCase = new StringBuilder();
                for (String part : parts) {
                    if (titleCase.length() > 0) titleCase.append(" ");
                    titleCase.append(part.substring(0, 1).toUpperCase()).append(part.substring(1));
                }
                blockType = titleCase.toString();
            }

            if (useThreading.get() && limitChatSpam.get()) {
                if (newDetections > 0) {
                    final int finalNewDetections = newDetections;
                    final String finalBlockType = blockType;
                    mc.execute(() -> {
                        if (mc.player != null) {
                            mc.player.sendMessage(Text.of("Filled holes: " + finalNewDetections + " " + finalBlockType + " blocks in chunk " + cpos.x + "," + cpos.z), false);
                        }
                    });
                }
            } else {
                final int finalDetections = chunkDetections.size();
                final String finalBlockType = blockType;
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.sendMessage(Text.of("Filled holes: " + finalDetections + " " + finalBlockType + " blocks in chunk " + cpos.x + "," + cpos.z), false);
                    }
                });
            }
        }
    }

    private boolean isFilledHole(BlockPos pos) {
        if (mc.world == null) return false;


        if (pos.getY() < minYLevel.get() || pos.getY() > maxYLevel.get()) {
            return false;
        }

        BlockState state = mc.world.getBlockState(pos);
        Block block = state.getBlock();


        if (isAirBlock(block)) {
            return false;
        }


        if (!isFillerBlock(block)) {
            return false;
        }


        BlockPos columnBase = findColumnBase(pos);


        int columnHeight = measureColumnHeightFromBase(columnBase);
        if (columnHeight < columnHeightThreshold.get()) {
            return false;
        }



        return isColumnSurrounded(columnBase, columnHeight);
    }


    private boolean isColumnSurrounded(BlockPos base, int height) {
        if (mc.world == null) return false;

        int requiredSides = minSideCoverage.get();
        int properlyEnclosed = 0;


        for (int i = 0; i < height; i++) {
            BlockPos checkPos = base.up(i);
            if (countCoveredSides(checkPos) >= requiredSides) {
                properlyEnclosed++;
            }
        }


        int requiredEnclosed = (height + 1) / 2;
        return properlyEnclosed >= requiredEnclosed;
    }


    private int measureColumnHeightFromBase(BlockPos base) {
        if (mc.world == null) return 0;

        int height = 0;
        BlockPos current = base;

        while (true) {
            Block block = mc.world.getBlockState(current).getBlock();


            if (isAirBlock(block) || !isFillerBlock(block)) {
                break;
            }

            height++;
            current = current.up();
        }

        return height;
    }

    private boolean isHoleNearChunk(ChunkPos chunkPos, int maxDistance) {
        if (mc.world == null) {
            return false;
        }

        int centerX = chunkPos.getStartX() + 8;
        int centerZ = chunkPos.getStartZ() + 8;


        for (int x = centerX - maxDistance; x <= centerX + maxDistance; x++) {
            for (int z = centerZ - maxDistance; z <= centerZ + maxDistance; z++) {
                for (int y = Math.max(mc.world.getBottomY(), minYLevel.get()); y <= Math.min(mc.world.getTopYInclusive(), maxYLevel.get()); y++) {
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

private boolean isAirBlock(Block block) {
        return block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR;
    }

    private boolean isFluidBlock(Block block) {
        return block == Blocks.WATER || block == Blocks.LAVA;
    }

    private int countCoveredSides(BlockPos pos) {
        if (mc.world == null) return 0;

        Block centerBlock = mc.world.getBlockState(pos).getBlock();
        int coveredSides = 0;


        BlockState northState = mc.world.getBlockState(pos.north());
        Block northBlock = northState.getBlock();
        if (isSideCovered(northBlock, centerBlock)) {
            coveredSides++;
        }


        BlockState southState = mc.world.getBlockState(pos.south());
        Block southBlock = southState.getBlock();
        if (isSideCovered(southBlock, centerBlock)) {
            coveredSides++;
        }


        BlockState eastState = mc.world.getBlockState(pos.east());
        Block eastBlock = eastState.getBlock();
        if (isSideCovered(eastBlock, centerBlock)) {
            coveredSides++;
        }


        BlockState westState = mc.world.getBlockState(pos.west());
        Block westBlock = westState.getBlock();
        if (isSideCovered(westBlock, centerBlock)) {
            coveredSides++;
        }

        return coveredSides;
    }

    private boolean isSideCovered(Block neighborBlock, Block centerBlock) {

        if (!isFillerBlock(neighborBlock) && !isAirBlock(neighborBlock) && !isFluidBlock(neighborBlock)) {
            return true;
        }


        if (isFillerBlock(neighborBlock) && neighborBlock != centerBlock) {
            return true;
        }

        return false;
    }


    private boolean areAllCardinalNeighborsNonFiller(BlockPos pos) {
        if (mc.world == null) return false;

        int requiredSides = minSideCoverage.get();
        int coveredSides = countCoveredSides(pos);

        return coveredSides >= requiredSides;
    }


    private BlockPos findColumnBase(BlockPos pos) {
        if (mc.world == null) return pos;

        BlockPos current = pos;
        while (true) {
            BlockPos below = current.down();
            Block blockBelow = mc.world.getBlockState(below).getBlock();

            if (blockBelow == Blocks.AIR || blockBelow == Blocks.CAVE_AIR || blockBelow == Blocks.VOID_AIR || !isFillerBlock(blockBelow)) {
                return current;
            }
            current = below;
        }
    }


    private List<BlockPos> getColumnBlocks(BlockPos pos) {
        List<BlockPos> blocks = new ArrayList<>();
        if (mc.world == null) return blocks;

        BlockPos base = findColumnBase(pos);
        BlockPos current = base;


        while (true) {
            Block block = mc.world.getBlockState(current).getBlock();


            if (isAirBlock(block) || !isFillerBlock(block)) {
                break;
            }

            blocks.add(current);
            current = current.up();
        }

        return blocks;
    }

    private boolean isFillerBlock(Block block) {
        if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
            return false;
        }


        return fillerBlocks.get().contains(block);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        Vec3d playerPos = mc.player.getLerpedPos(event.tickDelta);
        Color blockColorVal = new Color(blockColor.get());
        Color chunkColorVal = new Color(chunkColor.get());
        Color tracerColorVal = new Color(tracerColor.get());


        if (renderIndividualBlocks.get()) {
            for (BlockPos pos : detectedBlocks) {
                event.renderer.box(pos, blockColorVal, blockColorVal, shapeMode.get(), 0);


                if (tracers.get()) {
                    Vec3d blockCenter = Vec3d.ofCenter(pos);
                    Vec3d startPos = getTracerStartPos(playerPos, event.tickDelta);
                    event.renderer.line(startPos.x, startPos.y, startPos.z, blockCenter.x, blockCenter.y, blockCenter.z, tracerColorVal);
                }
            }
        }


        if (renderChunkHighlight.get()) {
            for (ChunkPos cpos : detectedChunks) {
                renderChunkBox(event, cpos, chunkColorVal);
            }
        }
    }

    private Vec3d getTracerStartPos(Vec3d playerPos, float tickDelta) {
        if (mc.options.getPerspective().isFirstPerson()) {
            Vec3d lookDirection = mc.player.getRotationVector();
            return new Vec3d(
                playerPos.x + lookDirection.x * 0.5,
                playerPos.y + mc.player.getEyeHeight(mc.player.getPose()) + lookDirection.y * 0.5,
                playerPos.z + lookDirection.z * 0.5
            );
        } else {
            return new Vec3d(
                playerPos.x,
                playerPos.y + mc.player.getEyeHeight(mc.player.getPose()),
                playerPos.z
            );
        }
    }

    private void renderChunkBox(Render3DEvent event, ChunkPos cpos, Color color) {
        int x1 = cpos.getStartX();
        int z1 = cpos.getStartZ();
        int x2 = cpos.getEndX();
        int z2 = cpos.getEndZ();
        int y1 = minYLevel.get();
        int y2 = maxYLevel.get();


        double x1d = x1, x2d = x2 + 1, y1d = y1, y2d = y2 + 1, z1d = z1, z2d = z2 + 1;


        event.renderer.line(x1d, y1d, z1d, x2d, y1d, z1d, color);
        event.renderer.line(x2d, y1d, z1d, x2d, y1d, z2d, color);
        event.renderer.line(x2d, y1d, z2d, x1d, y1d, z2d, color);
        event.renderer.line(x1d, y1d, z2d, x1d, y1d, z1d, color);


        event.renderer.line(x1d, y2d, z1d, x2d, y2d, z1d, color);
        event.renderer.line(x2d, y2d, z1d, x2d, y2d, z2d, color);
        event.renderer.line(x2d, y2d, z2d, x1d, y2d, z2d, color);
        event.renderer.line(x1d, y2d, z2d, x1d, y2d, z1d, color);


        event.renderer.line(x1d, y1d, z1d, x1d, y2d, z1d, color);
        event.renderer.line(x2d, y1d, z1d, x2d, y2d, z1d, color);
        event.renderer.line(x2d, y1d, z2d, x2d, y2d, z2d, color);
        event.renderer.line(x1d, y1d, z2d, x1d, y2d, z2d, color);
    }
}
