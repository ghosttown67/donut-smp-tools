package com.donutsmp.addon.modules.esp;

import com.donutsmp.addon.DonutSMPTools;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;

public class SweetBerryESP extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("ESP box color")
        .defaultValue(new SettingColor(220, 20, 60, 100))
        .build());

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("ESP box render mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final SettingGroup sgAges = settings.createGroup("Ages");

    private final Setting<Boolean> age0 = sgAges.add(new BoolSetting.Builder()
        .name("age-0")
        .description("Flag age 0 berry bushes")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> age1 = sgAges.add(new BoolSetting.Builder()
        .name("age-1")
        .description("Flag age 1 berry bushes")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> age2 = sgAges.add(new BoolSetting.Builder()
        .name("age-2")
        .description("Flag age 2 berry bushes")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> age3 = sgAges.add(new BoolSetting.Builder()
        .name("age-3")
        .description("Flag age 3 berry bushes")
        .defaultValue(true)
        .build());

    private final Set<BlockPos> berries = new HashSet<>();

    public SweetBerryESP() {
        super(DonutSMPTools.BASE_HUNTING_CATEGORY, "sweet-berry-esp", "Sweet berry ESP");
    }

    @Override
    public void onDeactivate() {
        berries.clear();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        scanChunk((WorldChunk) event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockState state = event.newState;
        if (state.isOf(Blocks.SWEET_BERRY_BUSH)) {
            int age = state.get(SweetBerryBushBlock.AGE);
            if (shouldFlag(age)) {
                berries.add(event.pos);
            } else {
                berries.remove(event.pos);
            }
        } else {
            berries.remove(event.pos);
        }
    }

    private void scanChunk(WorldChunk chunk) {
        int xStart = chunk.getPos().getStartX();
        int zStart = chunk.getPos().getStartZ();

        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                for (int y = chunk.getBottomY(); y < chunk.getBottomY() + chunk.getHeight(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.isOf(Blocks.SWEET_BERRY_BUSH)) {
                        int age = state.get(SweetBerryBushBlock.AGE);
                        if (shouldFlag(age)) {
                            berries.add(pos);
                        }
                    }
                }
            }
        }
    }

    private boolean shouldFlag(int age) {
        return (age == 0 && age0.get()) || (age == 1 && age1.get()) ||
               (age == 2 && age2.get()) || (age == 3 && age3.get());
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        Color boxColor = new Color(color.get());

        for (BlockPos pos : berries) {
            event.renderer.box(pos, boxColor, boxColor, shapeMode.get(), 0);
        }
    }
}
