package me.roan.custommusicdiscs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.JukeboxPlayableComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class CustomMusicDiscsPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private NamespacedKey discNameKey;
    private NamespacedKey discUrlKey;
    private NamespacedKey discColorKey;
    private NamespacedKey discSoundKey;

    private Path generatedPackDir;
    private Path soundsDir;
    private Path soundsJsonPath;
    private Path packZipPath;
    private Path generatedDatapackDir;
    private Path jukeboxSongDir;
    private volatile byte[] packHash;
    private volatile HttpServer packServer;
    private final Map<String, String> activeJukeboxSounds = new HashMap<>();

    private static final Pattern SAFE_ID = Pattern.compile("[^a-z0-9_]+", Pattern.CASE_INSENSITIVE);

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.discNameKey = new NamespacedKey(this, "disc_name");
        this.discUrlKey = new NamespacedKey(this, "disc_url");
        this.discColorKey = new NamespacedKey(this, "disc_color");
        this.discSoundKey = new NamespacedKey(this, "disc_sound");
        getServer().getPluginManager().registerEvents(this, this);

        this.generatedPackDir = getDataFolder().toPath().resolve("generated-pack");
        this.soundsDir = generatedPackDir.resolve("assets/custommusicdiscs/sounds");
        this.soundsJsonPath = generatedPackDir.resolve("assets/custommusicdiscs/sounds.json");
        this.packZipPath = getDataFolder().toPath().resolve("pack.zip");
        this.generatedDatapackDir = resolveDatapackDirectory();
        this.jukeboxSongDir = generatedDatapackDir.resolve("data/custommusicdiscs/jukebox_song");

        try {
            Files.createDirectories(soundsDir);
            ensureBasePackFiles();
            rebuildPackZip();
            ensureDatapackBaseFiles();
        } catch (IOException exception) {
            getLogger().severe("Failed to initialize generated resource pack: " + exception.getMessage());
        }

        if (getConfig().getBoolean("resource-pack.http.enabled", true)) {
            startPackServer();
        }

        if (getCommand("customdisc") != null) {
            getCommand("customdisc").setExecutor(this);
            getCommand("customdisc").setTabCompleter(this);
        } else {
            getLogger().severe("Command 'customdisc' is not defined in plugin.yml");
        }
    }

    @Override
    public void onDisable() {
        if (packServer != null) {
            packServer.stop(0);
            packServer = null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /customdisc create <name> <url.ogg> <color> | /customdisc pack", NamedTextColor.RED));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("pack")) {
            applyPackToPlayer(player);
            return true;
        }

        if (args.length == 3) {
            createCustomDisc(player, args[0], args[1], args[2]);
            return true;
        }

        if (args.length >= 4 && args[0].equalsIgnoreCase("create")) {
            createCustomDisc(player, args[1], args[2], args[3]);
            return true;
        }

        player.sendMessage(Component.text("Usage: /customdisc create <name> <url.ogg> <color> | /customdisc pack", NamedTextColor.RED));
        return true;
    }

    private void createCustomDisc(Player player, String discName, String url, String colorInput) {
        if (!isValidOggUrl(url)) {
            player.sendMessage(Component.text("The URL must be a valid http/https URL ending in .ogg", NamedTextColor.RED));
            return;
        }

        TextColor color = parseColor(colorInput);
        if (color == null) {
            player.sendMessage(Component.text("Invalid color. Use a named color (e.g. red) or hex like #FF55AA.", NamedTextColor.RED));
            return;
        }

        String soundId = toSoundId(discName);
        player.sendMessage(Component.text("Downloading and packaging sound...", NamedTextColor.YELLOW));

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    byte[] audioBytes = downloadAudio(url);
                    saveSoundFile(soundId, audioBytes);
                    saveJukeboxSongDefinition(soundId, discName);
                    rebuildPackZip();

                    Bukkit.getScheduler().runTask(CustomMusicDiscsPlugin.this, () -> {
                        if (getConfig().getBoolean("playback.use-vanilla-jukebox", true)) {
                            getServer().reloadData();
                        }

                        ItemStack disc = buildDiscItem(discName, url, colorInput, color, soundId);
                        player.getInventory().addItem(disc);
                        player.sendMessage(Component.text("Created custom disc '" + discName + "' with sound key custommusicdiscs:" + soundId, NamedTextColor.GREEN));
                        applyPackToPlayer(player);
                        player.sendMessage(Component.text("Sent updated resource pack. Accept/reload it before testing the disc.", NamedTextColor.AQUA));
                    });
                } catch (Exception exception) {
                    Bukkit.getScheduler().runTask(CustomMusicDiscsPlugin.this, () ->
                            player.sendMessage(Component.text("Failed to create disc: " + exception.getMessage(), NamedTextColor.RED))
                    );
                }
            }
        }.runTaskAsynchronously(this);
    }

    private ItemStack buildDiscItem(String discName, String url, String colorInput, TextColor color, String soundId) {
        ItemStack disc = new ItemStack(Material.MUSIC_DISC_11);
        ItemMeta meta = disc.getItemMeta();

        meta.displayName(Component.text(discName, Style.style(color)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("URL: " + url, NamedTextColor.GRAY));
        lore.add(Component.text("Color: " + colorInput, NamedTextColor.DARK_GRAY));
        lore.add(Component.text("Sound: custommusicdiscs:" + soundId, NamedTextColor.DARK_AQUA));
        meta.lore(lore);

        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(discNameKey, PersistentDataType.STRING, discName);
        container.set(discUrlKey, PersistentDataType.STRING, url);
        container.set(discColorKey, PersistentDataType.STRING, colorInput);
        container.set(discSoundKey, PersistentDataType.STRING, soundId);

        if (getConfig().getBoolean("playback.use-vanilla-jukebox", true)) {
            JukeboxPlayableComponent jukeboxPlayable = meta.getJukeboxPlayable();
            if (jukeboxPlayable != null) {
                jukeboxPlayable.setSongKey(new NamespacedKey("custommusicdiscs", soundId));
                jukeboxPlayable.setShowInTooltip(true);
                meta.setJukeboxPlayable(jukeboxPlayable);
            }
        }

        disc.setItemMeta(meta);
        return disc;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("customdisc")) {
            return List.of();
        }

        if (args.length == 1) {
            return Arrays.asList("create", "pack");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return List.of("name");
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return List.of("https://example.com/audio.ogg");
        }

        if ((args.length == 3 && !args[0].equalsIgnoreCase("create")) || (args.length == 4 && args[0].equalsIgnoreCase("create"))) {
            return Arrays.asList(
                    "red", "gold", "yellow", "green", "aqua", "blue", "light_purple", "white", "#FF55AA"
            );
        }

        return List.of();
    }

    @EventHandler
    public void onDiscUseOnJukebox(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || clickedBlock.getType() != Material.JUKEBOX) {
            return;
        }

        if (!getConfig().getBoolean("playback.use-vanilla-jukebox", true) && clickedBlock.getState() instanceof Jukebox jukeboxState) {
            ItemStack currentRecord = jukeboxState.getRecord();
            String currentSoundId = getStoredValue(currentRecord, discSoundKey);
            ItemStack handItem = event.getItem();

            if (currentSoundId != null && !currentSoundId.isBlank() && (handItem == null || handItem.getType() == Material.AIR)) {
                String key = getJukeboxKey(clickedBlock);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    stopCustomDiscForNearbyPlayers(clickedBlock.getLocation(), currentSoundId);
                    activeJukeboxSounds.remove(key);
                }, 1L);
                return;
            }
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        String url = getStoredValue(item, discUrlKey);
        if (url == null || url.isBlank()) {
            return;
        }

        String discName = getStoredValue(item, discNameKey);
        if (discName == null || discName.isBlank()) {
            discName = "Custom Disc";
        }

        String soundId = getStoredValue(item, discSoundKey);
        if (soundId == null || soundId.isBlank()) {
            playerWarnOldDisc(event.getPlayer());
            return;
        }

        if (getConfig().getBoolean("playback.use-vanilla-jukebox", true)) {
            return;
        }

        event.setCancelled(true);

        if (clickedBlock.getState() instanceof Jukebox jukebox && jukebox.getRecord().getType() == Material.AIR) {
            ItemStack oneDisc = item.clone();
            oneDisc.setAmount(1);
            jukebox.setRecord(oneDisc);
            jukebox.update(true, true);
            stopVanillaDiscForNearbyPlayers(clickedBlock.getLocation());
            activeJukeboxSounds.put(getJukeboxKey(clickedBlock), soundId);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                stopVanillaDiscForNearbyPlayers(clickedBlock.getLocation());
                playCustomDiscForNearbyPlayers(clickedBlock.getLocation(), soundId);
            }, 1L);

            item.setAmount(item.getAmount() - 1);
        }

        Player player = event.getPlayer();
        player.sendActionBar(Component.text("Now playing: " + discName + " | " + url, NamedTextColor.GOLD));
        player.sendMessage(
                Component.text("Now playing '" + discName + "' - ", NamedTextColor.GREEN)
                        .append(Component.text("open source URL", NamedTextColor.AQUA)
                                .clickEvent(ClickEvent.openUrl(url)))
                        .append(Component.text(" (from generated resource pack).", NamedTextColor.GRAY))
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (getConfig().getBoolean("resource-pack.auto-send-on-join", false)) {
            applyPackToPlayer(event.getPlayer());
        }
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> player.sendMessage(Component.text("Resource pack loaded successfully.", NamedTextColor.GREEN));
            case DECLINED -> player.sendMessage(Component.text("Resource pack declined. Custom disc sounds will be silent.", NamedTextColor.RED));
            case FAILED_DOWNLOAD -> player.sendMessage(Component.text("Resource pack download failed. Check public-url and port access.", NamedTextColor.RED));
            case INVALID_URL -> player.sendMessage(Component.text("Resource pack URL is invalid. Fix resource-pack.public-url.", NamedTextColor.RED));
            case FAILED_RELOAD -> player.sendMessage(Component.text("Resource pack reload failed on client.", NamedTextColor.RED));
            case DISCARDED -> player.sendMessage(Component.text("Resource pack was discarded by client.", NamedTextColor.RED));
            default -> player.sendMessage(Component.text("Resource pack status: " + event.getStatus(), NamedTextColor.YELLOW));
        }
    }

    private void playerWarnOldDisc(Player player) {
        player.sendMessage(Component.text("This disc has no generated sound key. Recreate it with /customdisc create.", NamedTextColor.RED));
    }

    private void stopVanillaDiscForNearbyPlayers(org.bukkit.Location location) {
        SoundStop stop = SoundStop.namedOnSource(Key.key("minecraft", "music_disc.11"), net.kyori.adventure.sound.Sound.Source.RECORD);
        double radiusSquared = getPlaybackRadiusSquared();

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.getWorld().equals(location.getWorld()) && onlinePlayer.getLocation().distanceSquared(location) <= radiusSquared) {
                onlinePlayer.stopSound(stop);
            }
        }
    }

    private void playCustomDiscForNearbyPlayers(org.bukkit.Location location, String soundId) {
        String namespacedKey = "custommusicdiscs:" + soundId;
        String dottedKey = "custommusicdiscs." + soundId;
        double radiusSquared = getPlaybackRadiusSquared();
        float volume = (float) getConfig().getDouble("playback.volume", 1.0D);

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.getWorld().equals(location.getWorld()) && onlinePlayer.getLocation().distanceSquared(location) <= radiusSquared) {
                onlinePlayer.playSound(location, namespacedKey, SoundCategory.RECORDS, volume, 1.0f);
                onlinePlayer.playSound(location, dottedKey, SoundCategory.RECORDS, volume, 1.0f);
            }
        }
    }

    private void stopCustomDiscForNearbyPlayers(org.bukkit.Location location, String soundId) {
        SoundStop namespacedStop = SoundStop.namedOnSource(Key.key("custommusicdiscs", soundId), net.kyori.adventure.sound.Sound.Source.RECORD);
        SoundStop dottedStop = SoundStop.namedOnSource(Key.key("custommusicdiscs", soundId.replace(':', '.')), net.kyori.adventure.sound.Sound.Source.RECORD);
        double radiusSquared = getPlaybackRadiusSquared();

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.getWorld().equals(location.getWorld()) && onlinePlayer.getLocation().distanceSquared(location) <= radiusSquared) {
                onlinePlayer.stopSound(namespacedStop);
                onlinePlayer.stopSound(dottedStop);
            }
        }
    }

    private double getPlaybackRadiusSquared() {
        double radius = getConfig().getDouble("playback.radius", 24.0D);
        if (radius < 4.0D) {
            radius = 4.0D;
        }
        return radius * radius;
    }

    private String getJukeboxKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private String getStoredValue(ItemStack itemStack, NamespacedKey key) {
        if (!itemStack.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = itemStack.getItemMeta();
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private boolean isValidOggUrl(String rawUrl) {
        try {
            URI uri = new URI(rawUrl);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return false;
            }

            String path = uri.getPath();
            return path != null && path.toLowerCase(Locale.ROOT).endsWith(".ogg");
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private byte[] downloadAudio(String rawUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(rawUrl).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(getConfig().getInt("download.connect-timeout-ms", 5000));
        connection.setReadTimeout(getConfig().getInt("download.read-timeout-ms", 15000));

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " from source URL");
        }

        int maxSize = getConfig().getInt("download.max-size-bytes", 20 * 1024 * 1024);
        try (InputStream inputStream = connection.getInputStream(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = inputStream.read(buffer)) != -1) {
                total += read;
                if (total > maxSize) {
                    throw new IOException("Audio exceeds max-size-bytes limit");
                }
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }

    private void saveSoundFile(String soundId, byte[] audioBytes) throws IOException {
        Files.createDirectories(soundsDir);
        Path target = soundsDir.resolve(soundId + ".ogg");
        Files.write(target, audioBytes);
    }

    private void saveJukeboxSongDefinition(String soundId, String discName) throws IOException {
        Files.createDirectories(jukeboxSongDir);
        double lengthSeconds = getConfig().getDouble("playback.default-length-seconds", 180.0D);
        if (lengthSeconds < 1.0D) {
            lengthSeconds = 1.0D;
        }

        Path songPath = jukeboxSongDir.resolve(soundId + ".json");
        String escapedName = discName.replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{" +
                "\n  \"sound_event\": { \"sound_id\": \"custommusicdiscs:" + soundId + "\" }," +
                "\n  \"description\": { \"text\": \"" + escapedName + "\" }," +
                "\n  \"length_in_seconds\": " + String.format(Locale.ROOT, "%.2f", lengthSeconds) + "," +
                "\n  \"comparator_output\": 12" +
                "\n}\n";
        Files.writeString(songPath, json, StandardCharsets.UTF_8);
    }

    private synchronized void rebuildPackZip() throws IOException {
        ensureBasePackFiles();
        writeSoundsJson();

        Path tempZip = getDataFolder().toPath().resolve("pack.zip.tmp");
        try (OutputStream fileOut = Files.newOutputStream(tempZip);
             ZipOutputStream zipOutputStream = new ZipOutputStream(fileOut)) {
            addDirectoryToZip(zipOutputStream, generatedPackDir, generatedPackDir);
        }

        Files.move(tempZip, packZipPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        this.packHash = sha1(Files.readAllBytes(packZipPath));
        getLogger().info("Generated resource pack at " + packZipPath + " (sha1=" + HexFormat.of().formatHex(packHash) + ")");
    }

    private void ensureBasePackFiles() throws IOException {
        Files.createDirectories(generatedPackDir);
        Files.createDirectories(generatedPackDir.resolve("assets/custommusicdiscs"));
        Files.createDirectories(soundsDir);

        int packFormat = getConfig().getInt("resource-pack.pack-format", 34);
        Path packMetaPath = generatedPackDir.resolve("pack.mcmeta");
        String content = "{" +
            "\n  \"pack\": {" +
            "\n    \"pack_format\": " + packFormat + "," +
            "\n    \"description\": \"CustomMusicDiscs generated pack\"" +
            "\n  }" +
            "\n}\n";
        Files.writeString(packMetaPath, content, StandardCharsets.UTF_8);
    }

    private void ensureDatapackBaseFiles() throws IOException {
        Files.createDirectories(generatedDatapackDir);
        Files.createDirectories(jukeboxSongDir);

        int dataPackFormat = getConfig().getInt("playback.datapack-format", 61);
        Path packMetaPath = generatedDatapackDir.resolve("pack.mcmeta");
        String content = "{" +
                "\n  \"pack\": {" +
                "\n    \"pack_format\": " + dataPackFormat + "," +
                "\n    \"description\": \"CustomMusicDiscs generated datapack\"" +
                "\n  }" +
                "\n}\n";
        Files.writeString(packMetaPath, content, StandardCharsets.UTF_8);
    }

    private Path resolveDatapackDirectory() {
        String worldName = getConfig().getString("playback.world-name", "world");
        return getServer().getWorldContainer().toPath()
                .resolve(worldName)
                .resolve("datapacks")
                .resolve("custommusicdiscs_generated");
    }

    private void writeSoundsJson() throws IOException {
        Files.createDirectories(soundsJsonPath.getParent());

        List<String> soundNames;
        try (var stream = Files.list(soundsDir)) {
            soundNames = stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                    .map(path -> path.getFileName().toString().substring(0, path.getFileName().toString().length() - 4))
                    .sorted()
                    .collect(Collectors.toList());
        }

        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        for (int i = 0; i < soundNames.size(); i++) {
            String sound = soundNames.get(i);
            builder.append("  \"").append(sound).append("\": {\n");
            builder.append("    \"sounds\": [{\"name\": \"custommusicdiscs:").append(sound).append("\", \"stream\": true}]\n");
            builder.append("  }");
            if (i < soundNames.size() - 1) {
                builder.append(",");
            }
            builder.append("\n");
        }
        builder.append("}\n");

        Files.writeString(soundsJsonPath, builder.toString(), StandardCharsets.UTF_8);
    }

    private void addDirectoryToZip(ZipOutputStream zipOutputStream, Path baseDir, Path currentDir) throws IOException {
        try (var stream = Files.list(currentDir)) {
            for (Path path : stream.toList()) {
                if (Files.isDirectory(path)) {
                    addDirectoryToZip(zipOutputStream, baseDir, path);
                    continue;
                }

                String entryName = baseDir.relativize(path).toString().replace('\\', '/');
                zipOutputStream.putNextEntry(new ZipEntry(entryName));
                Files.copy(path, zipOutputStream);
                zipOutputStream.closeEntry();
            }
        }
    }

    private byte[] sha1(byte[] content) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return digest.digest(content);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-1 not available", exception);
        }
    }

    private String toSoundId(String input) {
        String normalized = SAFE_ID.matcher(input.toLowerCase(Locale.ROOT)).replaceAll("_");
        normalized = normalized.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "disc";
        }
        return normalized;
    }

    private void startPackServer() {
        try {
            int port = getConfig().getInt("resource-pack.http.port", 8077);
            String path = getConfig().getString("resource-pack.http.path", "/pack.zip");
            if (!path.startsWith("/")) {
                path = "/" + path;
            }

            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            String finalPath = path;
            server.createContext(path, exchange -> handlePackRequest(exchange, finalPath));
            server.start();
            this.packServer = server;
            getLogger().info("Serving generated pack on http://0.0.0.0:" + port + path);
        } catch (IOException exception) {
            getLogger().severe("Failed to start resource-pack HTTP server: " + exception.getMessage());
        }
    }

    private void handlePackRequest(HttpExchange exchange, String configuredPath) throws IOException {
        try {
            if (!exchange.getRequestURI().getPath().equals(configuredPath)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            if (!Files.exists(packZipPath)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            byte[] content = Files.readAllBytes(packZipPath);
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(content);
            }
        } finally {
            exchange.close();
        }
    }

    private void applyPackToPlayer(Player player) {
        String url = getPackUrl();
        if (url == null || url.isBlank()) {
            player.sendMessage(Component.text("No resource-pack URL configured. Set resource-pack.public-url in config.yml", NamedTextColor.RED));
            return;
        }

        if (packHash == null || packHash.length == 0) {
            player.sendMessage(Component.text("Pack hash is not ready yet. Try again in a few seconds.", NamedTextColor.RED));
            return;
        }

        player.setResourcePack(url, packHash);
        player.sendMessage(Component.text("Sent generated resource pack: " + url, NamedTextColor.GREEN));
    }

    private String getPackUrl() {
        String configured = getConfig().getString("resource-pack.public-url", "").trim();
        if (!configured.isBlank()) {
            return configured;
        }

        int port = getConfig().getInt("resource-pack.http.port", 8077);
        String path = getConfig().getString("resource-pack.http.path", "/pack.zip");
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        return "http://127.0.0.1:" + port + path;
    }

    private TextColor parseColor(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        if (input.startsWith("#") && input.length() == 7) {
            try {
                return TextColor.fromHexString(input);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        String normalized = input.toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return NamedTextColor.NAMES.value(normalized.toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }
}
