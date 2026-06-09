package com.donutsmp.addon.modules.main;

import com.donutsmp.addon.DonutSMPTools;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

public class Relog extends Module {

    public Relog() {
        super(DonutSMPTools.MAIN_CATEGORY, "relog", "Auto relog to the current server.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.isInSingleplayer()) {
            toggle();
            return;
        }

        ServerInfo currentServer = mc.getCurrentServerEntry();
        if (currentServer == null) {
            toggle();
            return;
        }


        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection().disconnect(Text.literal("Relogging..."));
        }


        if (mc.world != null) {
            mc.world.disconnect();
        }


        mc.disconnect();


        new Thread(() -> {
            try {

                Thread.sleep(1500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            mc.execute(() -> {
                try {
                    ServerAddress serverAddr = ServerAddress.parse(currentServer.address);



                    ConnectScreen.connect(
                        new MultiplayerScreen(new TitleScreen()),
                        mc,
                        serverAddr,
                        currentServer,
                        false,
                        null
                    );
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }).start();

        toggle();
    }
}
