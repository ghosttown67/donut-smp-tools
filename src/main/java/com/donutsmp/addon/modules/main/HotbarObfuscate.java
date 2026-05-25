package com.donutsmp.addon.modules.main;

import com.donutsmp.addon.DonutSMPTools;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class HotbarObfuscate extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> obfuscateInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("obfuscate-inventory")
        .description("Also obfuscates items in the main inventory (not just hotbar).")
        .defaultValue(false)
        .build()
    );

    private ItemStack[] savedHotbarItems = new ItemStack[9];
    private ItemStack[] savedInventoryItems = new ItemStack[27];
    private boolean itemsSaved = false;

    public HotbarObfuscate() {
        super(DonutSMPTools.MAIN_CATEGORY, "hotbar-obfuscate", "Hides all items in your hotbar and optionally inventory.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // Save original items once
        if (!itemsSaved) {
            for (int i = 0; i < 9; i++) {
                savedHotbarItems[i] = mc.player.getInventory().getStack(i).copy();
            }
            if (obfuscateInventory.get()) {
                for (int i = 9; i < 36; i++) {
                    savedInventoryItems[i - 9] = mc.player.getInventory().getStack(i).copy();
                }
            }
            itemsSaved = true;
        }

        // Set all hotbar items to air
        for (int i = 0; i < 9; i++) {
            mc.player.getInventory().setStack(i, ItemStack.EMPTY);
        }

        // Set inventory items to air if option is enabled
        if (obfuscateInventory.get()) {
            for (int i = 9; i < 36; i++) {
                mc.player.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;

        // Restore original items
        for (int i = 0; i < 9; i++) {
            if (savedHotbarItems[i] != null) {
                mc.player.getInventory().setStack(i, savedHotbarItems[i]);
            }
        }

        if (obfuscateInventory.get()) {
            for (int i = 9; i < 36; i++) {
                if (savedInventoryItems[i - 9] != null) {
                    mc.player.getInventory().setStack(i, savedInventoryItems[i - 9]);
                }
            }
        }

        itemsSaved = false;
    }
}
