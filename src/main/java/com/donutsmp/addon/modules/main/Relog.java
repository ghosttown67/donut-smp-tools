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
        super(DonutSMPTools.MAIN_CATEGORY, "relog", "Auto-reconnects to the current server cleanly.");
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

        // 1. CLEAN DISCONNECT: Tell the server we are leaving so it doesn't leave a ghost player
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection().disconnect(Text.literal("Relogging..."));
        }

        // Ensure the client world cleans itself up
        if (mc.world != null) {
            mc.world.disconnect();
        }

        // 2. Clear local client state and safely transition the GUI
        mc.disconnect();

        // 3. Reconnect on a separate thread
        new Thread(() -> {
            try {
                // 1.5 seconds is plenty now that the server knows we left
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            mc.execute(() -> {
                try {
                    ServerAddress serverAddr = ServerAddress.parse(currentServer.address);

                    // We wrap the TitleScreen in a MultiplayerScreen so pressing "Cancel"
                    // while reconnecting takes you back to the server list, not the main menu
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
