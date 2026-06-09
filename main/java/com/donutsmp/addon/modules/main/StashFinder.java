package com.donutsmp.addon.modules.main;

import com.donutsmp.addon.DonutSMPTools;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class StashFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgNotification = settings.createGroup("Notification");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");


    private final Setting<java.util.List<Block>> storageBlocks = sgDetection.add(new BlockListSetting.Builder()
        .name("Storage Blocks")
        .description("Storage and redstone blocks to detect")
        .defaultValue(java.util.Arrays.asList(
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.BARREL,
            Blocks.SHULKER_BOX,
            Blocks.BLACK_SHULKER_BOX,
            Blocks.BLUE_SHULKER_BOX,
            Blocks.BROWN_SHULKER_BOX,
            Blocks.CYAN_SHULKER_BOX,
            Blocks.GRAY_SHULKER_BOX,
            Blocks.GREEN_SHULKER_BOX,
            Blocks.LIGHT_BLUE_SHULKER_BOX,
            Blocks.LIGHT_GRAY_SHULKER_BOX,
            Blocks.LIME_SHULKER_BOX,
            Blocks.MAGENTA_SHULKER_BOX,
            Blocks.ORANGE_SHULKER_BOX,
            Blocks.PINK_SHULKER_BOX,
            Blocks.PURPLE_SHULKER_BOX,
            Blocks.RED_SHULKER_BOX,
            Blocks.WHITE_SHULKER_BOX,
            Blocks.YELLOW_SHULKER_BOX,
            Blocks.ENDER_CHEST,
            Blocks.FURNACE,
            Blocks.BLAST_FURNACE,
            Blocks.SMOKER,
            Blocks.DISPENSER,
            Blocks.DROPPER,
            Blocks.HOPPER,
            Blocks.CRAFTER,
            Blocks.SPAWNER
        ))
        .build()
    );

    private final Setting<Double> minimumStorageCount = sgDetection.add(new DoubleSetting.Builder()
        .name("Min Storage Count")
        .description("Minimum number of storage blocks to trigger")
        .defaultValue(4.0)
        .min(1.0)
        .max(100.0)
        .sliderMax(50.0)
        .build()
    );

    private final Setting<Double> minimumDistance = sgDetection.add(new DoubleSetting.Builder()
        .name("Min Distance")
        .description("Minimum distance from spawn in blocks")
        .defaultValue(0.0)
        .min(0.0)
        .max(10000.0)
        .sliderMax(10000.0)
        .build()
    );

    private final Setting<Boolean> criticalSpawner = sgDetection.add(new BoolSetting.Builder()
        .name("Critical Spawner")
        .description("Always alert on spawner detection")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disconnectOnFind = sgGeneral.add(new BoolSetting.Builder()
        .name("Disconnect On Find")
        .description("Automatically disconnect when stash found")
        .defaultValue(false)
        .build()
    );


    private final Setting<Boolean> sendNotifications = sgNotification.add(new BoolSetting.Builder()
        .name("Send Notifications")
        .description("Send visual/audio notifications")
        .defaultValue(true)
        .build()
    );

    private final Setting<Mode> notificationMode = sgNotification.add(new EnumSetting.Builder<Mode>()
        .name("Notification Mode")
        .description("How to notify when stash found")
        .defaultValue(Mode.Both)
        .build()
    );


    private final Setting<Boolean> enableWebhook = sgWebhook.add(new BoolSetting.Builder()
        .name("Enable Webhook")
        .description("Send webhook notifications on stash detection")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> webhookUrl = sgWebhook.add(new StringSetting.Builder()
        .name("Webhook URL")
        .description("Discord webhook URL")
        .defaultValue("")
        .visible(enableWebhook::get)
        .build()
    );

    private final Setting<Boolean> selfPing = sgWebhook.add(new BoolSetting.Builder()
        .name("Self Ping")
        .description("Ping yourself in the webhook message")
        .defaultValue(false)
        .visible(enableWebhook::get)
        .build()
    );

    private final Setting<String> discordId = sgWebhook.add(new StringSetting.Builder()
        .name("Discord ID")
        .description("Your Discord user ID for pinging")
        .defaultValue("")
        .visible(() -> enableWebhook.get() && selfPing.get())
        .build()
    );

    private final Set<ChunkPos> processedChunks = new HashSet<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public StashFinder() {
        super(DonutSMPTools.BASE_HUNTING_CATEGORY, "StashFinder", "Finds and alerts on stashes with webhook support");
    }

    @Override
    public void onActivate() {
        processedChunks.clear();
    }

    @Override
    public void onDeactivate() {
        processedChunks.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        ChunkPos playerChunk = mc.player.getChunkPos();
        int renderDistance = mc.options.getViewDistance().getValue();

        for (int x = -renderDistance; x <= renderDistance; x++) {
            for (int z = -renderDistance; z <= renderDistance; z++) {
                ChunkPos chunkPos = new ChunkPos(playerChunk.x + x, playerChunk.z + z);

                if (processedChunks.contains(chunkPos)) continue;

                WorldChunk chunk = mc.world.getChunk(chunkPos.x, chunkPos.z);
                if (chunk != null) {
                    processChunk(chunk);
                    processedChunks.add(chunkPos);
                }
            }
        }
    }

    private void processChunk(WorldChunk worldChunk) {
        ChunkPos chunkPos = worldChunk.getPos();
        double chunkXAbs = Math.abs(chunkPos.x * 16.0);
        double chunkZAbs = Math.abs(chunkPos.z * 16.0);

        if (Math.sqrt(chunkXAbs * chunkXAbs + chunkZAbs * chunkZAbs) < minimumDistance.get()) return;

        java.util.List<Block> selectedBlocks = storageBlocks.get();
        int totalStorage = 0;
        int spawnersCount = 0;

        for (BlockEntity blockEntity : worldChunk.getBlockEntities().values()) {
            Block block = worldChunk.getBlockState(blockEntity.getPos()).getBlock();

            if (selectedBlocks.contains(block)) {
                if (block == Blocks.SPAWNER) {
                    spawnersCount++;
                } else {
                    totalStorage++;
                }
            }
        }

        boolean isStash = false;
        boolean isCriticalSpawner = false;
        String detectionReason = "";

        if (criticalSpawner.get() && spawnersCount > 0) {
            isStash = true;
            isCriticalSpawner = true;
            detectionReason = "Spawner(s) detected";
        } else if (totalStorage >= minimumStorageCount.get().intValue()) {
            isStash = true;
            detectionReason = "Storage x" + totalStorage;
        }

        if (isStash) {
            int x = chunkPos.x * 16 + 8;
            int z = chunkPos.z * 16 + 8;

            if (sendNotifications.get() && mc.player != null) {
                String stashType = isCriticalSpawner ? "spawner base" : "stash";
                String message = "Found " + stashType + " at " + x + ", " + z;

                if (notificationMode.get() == Mode.Chat || notificationMode.get() == Mode.Both) {
                    mc.player.sendMessage(Text.of("§a[Stash Finder] §f" + message), false);
                }

                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_BOTTLE_THROW, 1.0f, 1.0f);
            }

            if (enableWebhook.get()) {
                sendWebhookNotification(x, z, totalStorage, spawnersCount, isCriticalSpawner, detectionReason);
            }

            if (disconnectOnFind.get()) {
                toggle();
                if (mc.world != null) {
                    mc.world.disconnect();
                }
            }
        }
    }

    private void sendWebhookNotification(int x, int z, int totalStorage, int spawnersCount,
                                         boolean isCriticalSpawner, String detectionReason) {
        String url = webhookUrl.get().trim();
        if (url.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                String serverInfo = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "Unknown";
                String playerCoords = mc.player != null ? String.format("X: %.0f, Y: %.0f, Z: %.0f", mc.player.getX(), mc.player.getY(), mc.player.getZ()) : "Unknown";
                String stashType = isCriticalSpawner ? "Spawner Base" : "Stash";
                String messageContent = (selfPing.get() && !discordId.get().trim().isEmpty()) ? String.format("<@%s>", discordId.get().trim()) : "";


                StringBuilder fieldsJson = new StringBuilder();
                fieldsJson.append("{\"name\":\"📍 Location\",\"value\":\"").append(x).append(", ").append(z).append("\",\"inline\":true},");
                fieldsJson.append("{\"name\":\"👤 Player\",\"value\":\"").append(playerCoords).append("\",\"inline\":true},");
                fieldsJson.append("{\"name\":\"🏠 Server\",\"value\":\"").append(serverInfo).append("\",\"inline\":true},");
                fieldsJson.append("{\"name\":\"📦 Storage Count\",\"value\":\"").append(totalStorage).append("\",\"inline\":true},");

                if (spawnersCount > 0) {
                    fieldsJson.append("{\"name\":\"🕷 Spawners\",\"value\":\"").append(spawnersCount).append("\",\"inline\":true},");
                }

                fieldsJson.append("{\"name\":\"🔍 Detection\",\"value\":\"").append(detectionReason).append("\",\"inline\":true},");
                fieldsJson.append("{\"name\":\"⏰ Time\",\"value\":\"<t:").append(System.currentTimeMillis() / 1000).append(":R>\",\"inline\":true}");

                String jsonPayload = String.format(
                    "{\"content\":\"%s\"," +
                        "\"username\":\"StashFinder\"," +
                        "\"embeds\":[{" +
                        "\"title\":\"🎯 %s Alert\"," +
                        "\"description\":\"Found at **%d, %d**\"," +
                        "\"color\":16763904," +
                        "\"fields\":[%s]," +
                        "\"footer\":{\"text\":\"DonutSMP Tools\"}" +
                        "}]}",
                    messageContent, stashType, x, z, fieldsJson.toString()
                );

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .timeout(Duration.ofSeconds(30))
                    .build();

                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {}
        });
    }

    public enum Mode {
        Chat("Chat"),
        Toast("Toast"),
        Both("Both");

        Mode(String name) {}
    }
}
