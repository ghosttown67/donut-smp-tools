package com.donutsmp.addon;


import com.donutsmp.addon.hud.HudExample;
import com.donutsmp.addon.modules.esp.BeeNestESP;
import com.donutsmp.addon.modules.esp.SweetBerryESP;
import com.donutsmp.addon.modules.esp.FilledHolesESP;
import com.donutsmp.addon.modules.esp.HoleTunnelStairsESP;
import com.donutsmp.addon.modules.esp.CoveredHole;
import com.donutsmp.addon.modules.esp.VineESP;
import com.donutsmp.addon.modules.esp.KelpESP;
import com.donutsmp.addon.modules.esp.ClusterFinder;
import com.donutsmp.addon.modules.esp.DeepslateESP;
import com.donutsmp.addon.modules.esp.RotatedDeepslateESP;
import com.donutsmp.addon.modules.esp.DripstoneESP;
import com.donutsmp.addon.modules.esp.OneByOneHolesESP;
import com.donutsmp.addon.modules.esp.ChunkFinder;
import com.donutsmp.addon.modules.main.HideScoreboard;
import com.donutsmp.addon.modules.main.NoBlockInteract;
import com.donutsmp.addon.modules.main.Relog;
import com.donutsmp.addon.modules.main.StashFinder;
import com.donutsmp.addon.modules.esp.LightESP;
import com.donutsmp.addon.modules.main.RegionMap;
import com.donutsmp.addon.modules.main.HotbarObfuscate;
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
        Modules.get().add(new SweetBerryESP());
        Modules.get().add(new FilledHolesESP());
        Modules.get().add(new HoleTunnelStairsESP());
        Modules.get().add(new CoveredHole());
        Modules.get().add(new LightESP());
        Modules.get().add(new VineESP());
        Modules.get().add(new KelpESP());
        Modules.get().add(new ClusterFinder());
        Modules.get().add(new DeepslateESP());
        Modules.get().add(new RotatedDeepslateESP());
        Modules.get().add(new DripstoneESP());
        Modules.get().add(new OneByOneHolesESP());
        Modules.get().add(new ChunkFinder());
        Modules.get().add(new StashFinder());


        // Main category modules
        Modules.get().add(new HideScoreboard());
        Modules.get().add(new NoBlockInteract());
        Modules.get().add(new Relog());
        Modules.get().add(new RegionMap());
        Modules.get().add(new HotbarObfuscate());


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
