package com.cropfarm;

import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Chest-based crop browser. Six rows (54 slots): the top 45 hold crops,
 * the bottom 9 hold navigation. Crops are sorted by tier order (common → epic)
 * then by id, then paginated 45 per page.
 *
 * Click behaviour:
 *   - Left-click a crop  → details printed to chat.
 *   - Shift-click a crop → give one seed (requires cropfarm.give perm).
 *   - Slots 45 / 53      → previous / next page.
 *   - Slot 49            → close.
 *
 * The menu is identified via {@link CropMenuHolder} on the inventory, so we
 * never rely on the title string for routing.
 */
public class CropMenu implements Listener {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int PAGE_SIZE = 45;
    private static final int NAV_PREV_SLOT = 45;
    private static final int NAV_INFO_SLOT = 49;
    private static final int NAV_NEXT_SLOT = 53;
    private static final int NAV_CLOSE_SLOT = 48;

    /** Order in which tiers appear in the menu. Unknown tiers fall to the end. */
    private static final List<String> TIER_ORDER =
            List.of("common", "uncommon", "rare", "epic", "legendary", "mythic");

    private final CropFarm plugin;

    public CropMenu(CropFarm plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------

    public void open(Player player, int requestedPage) {
        List<CropType> sorted = sortedCrops();
        int totalPages = Math.max(1, (int) Math.ceil(sorted.size() / (double) PAGE_SIZE));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));

        CropMenuHolder holder = new CropMenuHolder(page);
        String title = "§8CropFarm — Page §f" + (page + 1) + "§8/§f" + totalPages;
        Inventory inv = Bukkit.createInventory(holder, SIZE, title);
        holder.setInventory(inv);

        int from = page * PAGE_SIZE;
        int to = Math.min(sorted.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            CropType type = sorted.get(i);
            inv.setItem(i - from, buildCropIcon(player, type));
        }

        // Navigation row (slots 45..53)
        if (page > 0) {
            inv.setItem(NAV_PREV_SLOT, navItem(Material.ARROW, "§e◀ Previous Page",
                    "§7Page " + page));
        } else {
            inv.setItem(NAV_PREV_SLOT, navFiller());
        }
        inv.setItem(NAV_INFO_SLOT, navItem(Material.KNOWLEDGE_BOOK,
                "§b✦ CropFarm",
                "§7Page §f" + (page + 1) + "§7/§f" + totalPages,
                "§7" + sorted.size() + " crops",
                "",
                "§7Left-click a crop for details.",
                "§7Shift-click to take a seed §8(op)§7."));
        inv.setItem(NAV_CLOSE_SLOT, navItem(Material.BARRIER, "§c✖ Close"));
        if (page < totalPages - 1) {
            inv.setItem(NAV_NEXT_SLOT, navItem(Material.ARROW, "§eNext Page ▶",
                    "§7Page " + (page + 2)));
        } else {
            inv.setItem(NAV_NEXT_SLOT, navFiller());
        }
        // Fill remaining nav slots with filler glass.
        for (int s = NAV_PREV_SLOT; s <= NAV_NEXT_SLOT; s++) {
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
        // first to prevent item movement. Do not move it down.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != top) return; // clicks in player's own inv: cancelled, no routing

        int slot = event.getSlot();
        int page = holder.getPage();

        if (slot == NAV_PREV_SLOT && page > 0) {
            playClick(player);
            open(player, page - 1);
            return;
        }
        if (slot == NAV_NEXT_SLOT) {
            playClick(player);
            open(player, page + 1);
            return;
        }
        if (slot == NAV_CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot >= PAGE_SIZE) return; // remaining nav slots: noop

        // Crop slot — resolve the crop by reading the icon's PDC.
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        String cropId = meta.getPersistentDataContainer()
                .get(plugin.getCropManager().cropTypeKey(), PersistentDataType.STRING);
        if (cropId == null) return;
        CropType type = plugin.getCropManager().getCropType(cropId);
        if (type == null) return;

        ClickType click = event.getClick();
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

        // Plain left-click → print details to chat.
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
    // Helpers
    // ---------------------------------------------------------------

    private List<CropType> sortedCrops() {
        List<CropType> all = new ArrayList<>(plugin.getCropManager().getCropTypes());
        all.sort(Comparator
                .comparingInt((CropType c) -> tierIndex(c.getTier()))
                .thenComparing(CropType::getId));
        return all;
    }

    private static int tierIndex(String tierId) {
        if (tierId == null) return TIER_ORDER.size();
        int i = TIER_ORDER.indexOf(tierId.toLowerCase());
        return i < 0 ? TIER_ORDER.size() : i;
    }

    private ItemStack buildCropIcon(Player player, CropType type) {
        // Use the primary output material so players recognise what the crop produces.
        Material icon = type.getPrimaryOutput();
        ItemStack item = new ItemStack(icon == null ? Material.WHEAT_SEEDS : icon);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        Tier tier = plugin.getCropManager().getTier(type.getTier());
        String tierColor = tier == null ? "§7" : tier.color();

        meta.setDisplayName(type.getDisplayName());

        List<String> lore = new ArrayList<>();
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
            for (CropType.DropEntry e : type.getOutputs()) {
                String range = e.minAmount() == e.maxAmount()
                        ? String.valueOf(e.minAmount())
                        : e.minAmount() + "-" + e.maxAmount();
                lore.add("  §7• §fw" + e.weight() + " §7→ §f" + range + " " + humanize(e.item().name()));
            }
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
        // Tag the icon with the crop id so click handler can resolve it without parsing names.
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

    private static ItemStack navItem(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            if (lore.length > 0) m.setLore(java.util.Arrays.asList(lore));
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

    /** "180" → "3 min", "7200" → "2h 0m", "45" → "45 sec". */
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

    /** "GOLD_INGOT" → "Gold Ingot". */
    private static String humanize(String enumName) {
        if (enumName == null || enumName.isEmpty()) return "";
        String[] words = enumName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Re-export for tests / external use. */
    @SuppressWarnings("unused")
    public static List<String> tierOrderView() {
        return Collections.unmodifiableList(TIER_ORDER);
    }
}
