package com.donutsmp.addon;


import com.donutsmp.addon.hud.HudExample;
import com.donutsmp.addon.modules.esp.BeeNestESP;
import com.donutsmp.addon.modules.esp.VineESP;
import com.donutsmp.addon.modules.esp.WanderingESP;
import com.donutsmp.addon.modules.esp.SweetBerryESP;
import com.donutsmp.addon.modules.esp.OneByOneHoles;
import com.donutsmp.addon.modules.esp.FilledHolesESP;
import com.donutsmp.addon.modules.esp.RotatedDeepslateESP;
import com.donutsmp.addon.modules.esp.HoleTunnelStairsESP;
import com.donutsmp.addon.modules.esp.CoveredHole;
import com.donutsmp.addon.modules.main.HideScoreboard;
import com.donutsmp.addon.modules.main.NoBlockInteract;
import com.donutsmp.addon.modules.main.Relog;
import com.donutsmp.addon.modules.esp.LightESP;
import com.donutsmp.addon.modules.other.RegionMap;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class DonutSMPTools extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("DonutSMP Tools");
    public static final Category BASE_HUNTING_CATEGORY = new Category("Base Hunting");
    public static final Category MAIN_CATEGORY = new Category("Main");
    public static final HudGroup HUD_GROUP = new HudGroup("DonutSMP Tools");

    @Override
    public void onInitialize() {



        Modules.get().add(new BeeNestESP());
        Modules.get().add(new VineESP());
        Modules.get().add(new WanderingESP());
        Modules.get().add(new SweetBerryESP());
        Modules.get().add(new OneByOneHoles());
        Modules.get().add(new FilledHolesESP());

        // New ESP modules
        Modules.get().add(new RotatedDeepslateESP());
        Modules.get().add(new HoleTunnelStairsESP());
        Modules.get().add(new CoveredHole());
        Modules.get().add(new LightESP());


        // Main category modules
        Modules.get().add(new HideScoreboard());
        Modules.get().add(new NoBlockInteract());
        Modules.get().add(new Relog());
        Modules.get().add(new RegionMap());

        LOG.info("Initializing DonutSMP Tools");




        // HUD
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
        Modules.registerCategory(BASE_HUNTING_CATEGORY);
        Modules.registerCategory(MAIN_CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.donutsmp.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("DonutSMP", "donutsmp-tools");
    }
}
