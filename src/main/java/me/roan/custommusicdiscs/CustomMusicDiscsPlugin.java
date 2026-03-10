package me.roan.custommusicdiscs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class CustomMusicDiscsPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private NamespacedKey discNameKey;
    private NamespacedKey discUrlKey;
    private NamespacedKey discColorKey;

    @Override
    public void onEnable() {
        this.discNameKey = new NamespacedKey(this, "disc_name");
        this.discUrlKey = new NamespacedKey(this, "disc_url");
        this.discColorKey = new NamespacedKey(this, "disc_color");

        if (getCommand("customdisc") != null) {
            getCommand("customdisc").setExecutor(this);
            getCommand("customdisc").setTabCompleter(this);
        } else {
            getLogger().severe("Command 'customdisc' is not defined in plugin.yml");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /customdisc <name> <url.ogg> <color>", NamedTextColor.RED));
            return true;
        }

        String discName = args[0];
        String url = args[1];
        String colorInput = args[2];

        if (!isValidOggUrl(url)) {
            player.sendMessage(Component.text("The URL must be a valid http/https URL ending in .ogg", NamedTextColor.RED));
            return true;
        }

        TextColor color = parseColor(colorInput);
        if (color == null) {
            player.sendMessage(Component.text("Invalid color. Use a named color (e.g. red) or hex like #FF55AA.", NamedTextColor.RED));
            return true;
        }

        ItemStack disc = new ItemStack(Material.MUSIC_DISC_11);
        ItemMeta meta = disc.getItemMeta();

        meta.displayName(Component.text(discName, Style.style(color)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("URL: " + url, NamedTextColor.GRAY));
        lore.add(Component.text("Color: " + colorInput, NamedTextColor.DARK_GRAY));
        meta.lore(lore);

        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(discNameKey, PersistentDataType.STRING, discName);
        container.set(discUrlKey, PersistentDataType.STRING, url);
        container.set(discColorKey, PersistentDataType.STRING, colorInput);

        disc.setItemMeta(meta);
        player.getInventory().addItem(disc);

        player.sendMessage(Component.text("Created custom disc '" + discName + "'.", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("customdisc")) {
            return List.of();
        }

        if (args.length == 3) {
            return Arrays.asList(
                    "red", "gold", "yellow", "green", "aqua", "blue", "light_purple", "white", "#FF55AA"
            );
        }

        return List.of();
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
