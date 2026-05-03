package com.cropfarm;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-level chest browser:
 *   MAIN     — grid of category cards, click any to drill in.
 *   CATEGORY — paginated crop list filtered to one category, with prev /
 *              back / info / next nav row.
 *
 * Categories come from each crop's `category` field, which CropManager
 * derives from the source filename (e.g. crops/blocks.yml → "blocks").
 *
 * Identifies its own inventories via {@link CropMenuHolder} (never by
 * title). The holder also carries the current mode + category + page so
 * click routing is purely inventory-state driven.
 *
 * Click safety invariant: setCancelled fires BEFORE any guard or early
 * return, so number-key swaps, double-click collect, F-swap, etc. cannot
 * exfiltrate items. Do not move the cancel down.
 */
public class CropMenu implements Listener {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int CONTENT_SLOTS = 45;          // 0..44 (5 rows)
    private static final int NAV_PREV_SLOT  = 45;
    private static final int NAV_BACK_SLOT  = 47;
    private static final int NAV_CLOSE_SLOT = 48;
    private static final int NAV_INFO_SLOT  = 49;
    private static final int NAV_NEXT_SLOT  = 53;

    /** Tier display order (and sort priority within a category page). */
    private static final List<String> TIER_ORDER =
            List.of("common", "uncommon", "rare", "epic", "legendary", "mythic");

    /**
     * Display order for known categories (matches the typical play
     * progression). Unknown categories fall to the end alphabetically.
     */
    private static final List<String> CATEGORY_ORDER = List.of(
            "ores", "raw-ores", "crafting-essentials", "blocks",
            "concrete", "terracotta",
            "saplings", "farm-crops", "foods", "mushrooms",
            "vanilla", "wool", "flowers", "dyes",
            "decoratives", "mob-drops", "heads", "pottery",
            "froglights", "sniffer", "breeze-trial", "endgame"
    );

    /** Recognisable icon for each known category — clearer than auto-derived. */
    private static final Map<String, Material> CATEGORY_ICONS = Map.ofEntries(
            Map.entry("ores",                Material.DIAMOND),
            Map.entry("raw-ores",            Material.RAW_IRON),
            Map.entry("crafting-essentials", Material.STICK),
            Map.entry("blocks",              Material.COBBLESTONE),
            Map.entry("concrete",            Material.LIGHT_BLUE_CONCRETE),
            Map.entry("terracotta",          Material.ORANGE_TERRACOTTA),
            Map.entry("saplings",            Material.OAK_SAPLING),
            Map.entry("farm-crops",          Material.WHEAT),
            Map.entry("foods",               Material.COOKED_BEEF),
            Map.entry("mushrooms",           Material.RED_MUSHROOM),
            Map.entry("vanilla",             Material.APPLE),
            Map.entry("wool",                Material.WHITE_WOOL),
            Map.entry("flowers",             Material.POPPY),
            Map.entry("dyes",                Material.RED_DYE),
            Map.entry("decoratives",         Material.SPONGE),
            Map.entry("mob-drops",           Material.ROTTEN_FLESH),
            Map.entry("heads",               Material.SKELETON_SKULL),
            Map.entry("pottery",             Material.BRICK),
            Map.entry("froglights",          Material.OCHRE_FROGLIGHT),
            Map.entry("sniffer",             Material.SNIFFER_EGG),
            Map.entry("breeze-trial",        Material.BREEZE_ROD),
            Map.entry("endgame",             Material.NETHER_STAR)
    );

    private final CropFarm plugin;
    private final NamespacedKey categoryKey;

    public CropMenu(CropFarm plugin) {
        this.plugin = plugin;
        this.categoryKey = new NamespacedKey(plugin, "menu_category");
    }

    // ---------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------

    /** Convenience: open the main category menu. */
    public void open(Player player) {
        openMainMenu(player);
    }

    public void openMainMenu(Player player) {
        CropMenuHolder holder = new CropMenuHolder(CropMenuHolder.Mode.MAIN, null, 0);
        Inventory inv = Bukkit.createInventory(holder, SIZE, "§8CropFarm — §fCategories");
        holder.setInventory(inv);

        List<CategoryEntry> categories = enumerateCategories();
        for (int i = 0; i < categories.size() && i < CONTENT_SLOTS; i++) {
            inv.setItem(i, buildCategoryCard(categories.get(i)));
        }

        // Nav row
        inv.setItem(NAV_INFO_SLOT, navItem(Material.KNOWLEDGE_BOOK,
                "§b✦ CropFarm",
                "§7" + categories.size() + " categories",
                "§7" + plugin.getCropManager().getCropTypes().size() + " crops total",
                "",
                "§7Click a category to browse it."));
        inv.setItem(NAV_CLOSE_SLOT, navItem(Material.BARRIER, "§c✖ Close"));
        for (int s = CONTENT_SLOTS; s < SIZE; s++) {
            if (inv.getItem(s) == null) inv.setItem(s, navFiller());
        }

        player.openInventory(inv);
    }

    public void openCategory(Player player, String category, int requestedPage) {
        List<CropType> sorted = sortedCropsInCategory(category);
        int totalPages = Math.max(1, (int) Math.ceil(sorted.size() / (double) CONTENT_SLOTS));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));

        CropMenuHolder holder = new CropMenuHolder(CropMenuHolder.Mode.CATEGORY, category, page);
        String title = "§8" + prettyCategory(category)
                + " §8— §fPage " + (page + 1) + "§8/§f" + totalPages;
        Inventory inv = Bukkit.createInventory(holder, SIZE, title);
        holder.setInventory(inv);

        int from = page * CONTENT_SLOTS;
        int to = Math.min(sorted.size(), from + CONTENT_SLOTS);
        for (int i = from; i < to; i++) {
            inv.setItem(i - from, buildCropIcon(player, sorted.get(i)));
        }

        // Nav row
        if (page > 0) {
            inv.setItem(NAV_PREV_SLOT, navItem(Material.ARROW, "§e◀ Previous Page",
                    "§7Page " + page));
        }
        inv.setItem(NAV_BACK_SLOT, navItem(Material.OAK_DOOR, "§e⏎ Back to Categories"));
        inv.setItem(NAV_CLOSE_SLOT, navItem(Material.BARRIER, "§c✖ Close"));
        inv.setItem(NAV_INFO_SLOT, navItem(Material.KNOWLEDGE_BOOK,
                "§b✦ " + prettyCategory(category),
                "§7Page §f" + (page + 1) + "§7/§f" + totalPages,
                "§7" + sorted.size() + " crops",
                "",
                "§7Left-click for details.",
                "§7Shift-click to take a seed §8(op)§7."));
        if (page < totalPages - 1) {
            inv.setItem(NAV_NEXT_SLOT, navItem(Material.ARROW, "§eNext Page ▶",
                    "§7Page " + (page + 2)));
        }
        for (int s = CONTENT_SLOTS; s < SIZE; s++) {
            if (inv.getItem(s) == null) inv.setItem(s, navFiller());
        }

        player.openInventory(inv);
    }

    // ---------------------------------------------------------------
    // Click handling
    // ---------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CropMenuHolder holder)) return;

        // INVARIANT: cancel BEFORE any subsequent guards or early-returns.
        // Number-key (hotbar swap), F (swap-offhand), shift-click, and
        // double-click "collect-to-cursor" all rely on this cancel firing
        // first. Do not move it down.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != top) return;

        int slot = event.getSlot();

        if (holder.getMode() == CropMenuHolder.Mode.MAIN) {
            handleMainClick(player, slot);
        } else {
            handleCategoryClick(player, holder, slot, event.getClick());
        }
    }

    private void handleMainClick(Player player, int slot) {
        if (slot == NAV_CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot >= CONTENT_SLOTS) return; // other nav slots: noop

        // Read the category id from the card's PDC — robust against any
        // reordering between menu render and click.
        Inventory inv = player.getOpenInventory().getTopInventory();
        ItemStack clicked = inv.getItem(slot);
        if (clicked == null || !clicked.hasItemMeta()) return;
        String catId = clicked.getItemMeta().getPersistentDataContainer()
                .get(categoryKey, PersistentDataType.STRING);
        if (catId == null) return;
        playClick(player);
        openCategory(player, catId, 0);
    }

    private void handleCategoryClick(Player player, CropMenuHolder holder, int slot, ClickType click) {
        if (slot == NAV_BACK_SLOT) {
            playClick(player);
            openMainMenu(player);
            return;
        }
        if (slot == NAV_CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == NAV_PREV_SLOT && holder.getPage() > 0) {
            playClick(player);
            openCategory(player, holder.getCategory(), holder.getPage() - 1);
            return;
        }
        if (slot == NAV_NEXT_SLOT) {
            playClick(player);
            openCategory(player, holder.getCategory(), holder.getPage() + 1);
            return;
        }
        if (slot >= CONTENT_SLOTS) return;

        ItemStack clicked = holder.getInventory().getItem(slot);
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        String cropId = meta.getPersistentDataContainer()
                .get(plugin.getCropManager().cropTypeKey(), PersistentDataType.STRING);
        if (cropId == null) return;
        CropType type = plugin.getCropManager().getCropType(cropId);
        if (type == null) return;

        if (click.isShiftClick()) {
            if (!player.hasPermission("cropfarm.give")) {
                player.sendMessage("§cYou don't have permission to take seeds.");
                return;
            }
            ItemStack seed = plugin.getCropManager().createSeed(type, 1);
            var leftover = player.getInventory().addItem(seed);
            if (!leftover.isEmpty()) {
                player.sendMessage("§c⚠ Inventory full — couldn't fit the seed.");
            } else {
                player.sendMessage("§a✦ Took 1 " + type.getDisplayName() + "§a.");
                playClick(player);
            }
            return;
        }

        sendDetails(player, type);
        playClick(player);
    }

    /** Cancel any drag that touches the menu inventory (read-only protection). */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CropMenuHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---------------------------------------------------------------
    // Category enumeration / sorting
    // ---------------------------------------------------------------

    private record CategoryEntry(String id, String displayName, Material icon, int cropCount) { }

    private List<CategoryEntry> enumerateCategories() {
        Map<String, List<CropType>> byCategory = new LinkedHashMap<>();
        for (CropType c : plugin.getCropManager().getCropTypes()) {
            String cat = c.getCategory() == null || c.getCategory().isEmpty() ? "other" : c.getCategory();
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(c);
        }

        List<CategoryEntry> entries = new ArrayList<>();
        for (var e : byCategory.entrySet()) {
            String id = e.getKey();
            Material icon = CATEGORY_ICONS.getOrDefault(id, fallbackIconFor(e.getValue()));
            entries.add(new CategoryEntry(id, prettyCategory(id), icon, e.getValue().size()));
        }
        entries.sort(Comparator
                .comparingInt((CategoryEntry c) -> categoryOrderIndex(c.id()))
                .thenComparing(CategoryEntry::displayName));
        return entries;
    }

    private static int categoryOrderIndex(String id) {
        int i = CATEGORY_ORDER.indexOf(id);
        return i < 0 ? CATEGORY_ORDER.size() : i;
    }

    private static Material fallbackIconFor(List<CropType> crops) {
        if (crops.isEmpty()) return Material.WHEAT_SEEDS;
        Material m = crops.get(0).getPrimaryOutput();
        return m == null ? Material.WHEAT_SEEDS : m;
    }

    private List<CropType> sortedCropsInCategory(String category) {
        List<CropType> out = new ArrayList<>();
        for (CropType c : plugin.getCropManager().getCropTypes()) {
            if (category.equals(c.getCategory()) ||
                    (c.getCategory() == null && "ores".equals(category))) {
                out.add(c);
            }
        }
        out.sort(Comparator
                .comparingInt((CropType c) -> tierIndex(c.getTier()))
                .thenComparing(CropType::getId));
        return out;
    }

    private static int tierIndex(String tierId) {
        if (tierId == null) return TIER_ORDER.size();
        int i = TIER_ORDER.indexOf(tierId.toLowerCase());
        return i < 0 ? TIER_ORDER.size() : i;
    }

    // ---------------------------------------------------------------
    // Card / icon builders
    // ---------------------------------------------------------------

    private ItemStack buildCategoryCard(CategoryEntry entry) {
        ItemStack item = new ItemStack(entry.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName("§b✦ §f" + entry.displayName());
        List<String> lore = new ArrayList<>();
        lore.add("§7" + entry.cropCount() + " crops");
        lore.add("");
        lore.add("§eClick to browse →");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(
                categoryKey, PersistentDataType.STRING, entry.id());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildCropIcon(Player player, CropType type) {
        Material icon = type.getPrimaryOutput();
        ItemStack item = new ItemStack(icon == null ? Material.WHEAT_SEEDS : icon);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        Tier tier = plugin.getCropManager().getTier(type.getTier());
        String tierColor = tier == null ? "§7" : tier.color();

        if (type.isEnabled()) {
            meta.setDisplayName(type.getDisplayName());
        } else {
            meta.setDisplayName("§8§m"
                    + org.bukkit.ChatColor.stripColor(type.getDisplayName())
                    + "§r §c[DISABLED]");
        }

        List<String> lore = new ArrayList<>();
        if (!type.isEnabled()) {
            lore.add("§c⚠ Disabled by server admin.");
            lore.add("");
        }
        lore.add("§8Tier: " + tierColor + (type.getTier() == null ? "—" : type.getTier()));
        lore.add("§8Grow time: §f" + formatGrowTime(type.getGrowTimeSeconds()));

        int planted = plugin.getTrackedCrops()
                .countFor(player.getUniqueId(), type.getId());
        if (type.getMaxPerPlayer() > 0) {
            String capColor = planted >= type.getMaxPerPlayer() ? "§c" : "§a";
            lore.add("§8Cap: " + capColor + planted + "§7/§f" + type.getMaxPerPlayer() + " §8planted");
        } else {
            lore.add("§8Cap: §a" + planted + " §8planted §7(unlimited)");
        }

        lore.add("§8Recipe: §f1 " + humanize(type.getRecipeInput().name())
                + " §7→§f " + type.getRecipeYield() + " seeds");

        if (type.getOutputs().size() == 1) {
            CropType.DropEntry only = type.getOutputs().get(0);
            String range = only.minAmount() == only.maxAmount()
                    ? String.valueOf(only.minAmount())
                    : only.minAmount() + "-" + only.maxAmount();
            lore.add("§8Drops: §f" + range + " " + humanize(only.item().name()));
        } else {
            lore.add("§8Drops: §f" + type.getOutputs().size() + " possible §7(weighted)");
        }

        if (type.getXpMax() > 0) {
            String xp = type.getXpMin() == type.getXpMax()
                    ? String.valueOf(type.getXpMin())
                    : type.getXpMin() + "-" + type.getXpMax();
            lore.add("§8XP per harvest: §a" + xp);
        }

        lore.add("");
        lore.add("§7Left-click for full recipe.");
        if (player.hasPermission("cropfarm.give")) {
            lore.add("§7Shift-click to take 1 seed.");
        }

        meta.setLore(lore);
        meta.getPersistentDataContainer().set(
                plugin.getCropManager().cropTypeKey(),
                PersistentDataType.STRING, type.getId());
        item.setItemMeta(meta);
        return item;
    }

    private void sendDetails(Player player, CropType type) {
        Tier tier = plugin.getCropManager().getTier(type.getTier());
        String tierColor = tier == null ? "§7" : tier.color();

        player.sendMessage("§8§m──────────");
        player.sendMessage(type.getDisplayName());
        player.sendMessage("§8Tier: " + tierColor + (type.getTier() == null ? "—" : type.getTier())
                + " §8| Grow time: §f" + formatGrowTime(type.getGrowTimeSeconds()));
        player.sendMessage("§8Recipe: §f1 " + humanize(type.getRecipeInput().name())
                + " §7→§f " + type.getRecipeYield() + " seeds");
        if (type.getOutputs().size() == 1) {
            CropType.DropEntry only = type.getOutputs().get(0);
            player.sendMessage("§8Drops: §f" + only.minAmount() + "-" + only.maxAmount()
                    + " " + humanize(only.item().name()));
        } else {
            player.sendMessage("§8Possible drops:");
            for (CropType.DropEntry e : type.getOutputs()) {
                player.sendMessage("  §7• §fweight " + e.weight() + " §7→ §f"
                        + e.minAmount() + "-" + e.maxAmount() + " " + humanize(e.item().name()));
            }
        }
        if (type.getXpMax() > 0) {
            player.sendMessage("§8XP per harvest: §a" + type.getXpMin() + "-" + type.getXpMax());
        }
        int planted = plugin.getTrackedCrops().countFor(player.getUniqueId(), type.getId());
        if (type.getMaxPerPlayer() > 0) {
            player.sendMessage("§8Your planted: §f" + planted + "§7/§f" + type.getMaxPerPlayer());
        } else {
            player.sendMessage("§8Your planted: §f" + planted + " §7(unlimited)");
        }
        player.sendMessage("§8§m──────────");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static ItemStack navItem(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            if (lore.length > 0) m.setLore(Arrays.asList(lore));
            it.setItemMeta(m);
        }
        return it;
    }

    private static ItemStack navFiller() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(" ");
            it.setItemMeta(m);
        }
        return it;
    }

    private static void playClick(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
    }

    /** "180" → "3 min", "172800" → "48h 0m". */
    private static String formatGrowTime(int seconds) {
        if (seconds < 60) return seconds + " sec";
        if (seconds < 3600) {
            int m = seconds / 60;
            int s = seconds % 60;
            return s == 0 ? m + " min" : m + " min " + s + " sec";
        }
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        return h + "h " + m + "m";
    }

    /** "GOLD_INGOT" → "Gold Ingot", "mob-drops" → "Mob Drops". */
    private static String humanize(String enumName) {
        if (enumName == null || enumName.isEmpty()) return "";
        String[] words = enumName.toLowerCase().split("[_-]");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Public so commands can resolve a display name from a category id. */
    public static String prettyCategory(String id) {
        return humanize(id);
    }

    /** Returns whether a given category id has any crops loaded. Used by tab completion. */
    public boolean categoryExists(String id) {
        if (id == null) return false;
        for (CropType c : plugin.getCropManager().getCropTypes()) {
            if (id.equals(c.getCategory())) return true;
        }
        return false;
    }

    /** Sorted list of category ids — for tab completion. */
    public List<String> listCategories() {
        List<String> out = new ArrayList<>();
        for (CategoryEntry e : enumerateCategories()) out.add(e.id());
        return out;
    }
}
