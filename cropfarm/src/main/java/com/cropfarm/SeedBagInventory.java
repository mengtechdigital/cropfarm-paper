package com.cropfarm;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Open + render + click-handle the seed bag chest UI.
 *
 * Layout (54-slot chest):
 *   Slots 0..44 — storage. Per-tier visible range:
 *      T1: 0..8 unlocked,  9..44 locked-preview
 *      T2: 0..26 unlocked, 27..44 locked-preview
 *      T3: 0..44 unlocked
 *      T4-6: 45 slots per page; multi-page nav uses prev/next buttons
 *   Slots 45..53 — fixed nav row:
 *      45 prev page | 46 filler | 47 deposit-all | 48 close
 *      49 bag info  | 50 upgrade | 51 filler | 52 filler | 53 next page
 *
 * Click invariant (mirrors CropMenu): setCancelled(true) BEFORE any guard
 * for nav-row clicks; storage-slot clicks selectively un-cancel after
 * validating the moving item is a CropFarm seed.
 */
public class SeedBagInventory {

    private static final int CLOSE_SLOT       = SeedBagTier.NAV_OFFSET + 3; // 48
    private static final int INFO_SLOT        = SeedBagTier.NAV_OFFSET + 4; // 49
    private static final int UPGRADE_SLOT     = SeedBagTier.NAV_OFFSET + 5; // 50
    private static final int DEPOSIT_ALL_SLOT = SeedBagTier.NAV_OFFSET + 2; // 47
    private static final int PREV_PAGE_SLOT   = SeedBagTier.NAV_OFFSET;     // 45
    private static final int NEXT_PAGE_SLOT   = SeedBagTier.NAV_OFFSET + 8; // 53

    private final CropFarm plugin;
    private final SeedBag seedBag;

    public SeedBagInventory(CropFarm plugin, SeedBag seedBag) {
        this.plugin = plugin;
        this.seedBag = seedBag;
    }

    // ---------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------

    public void open(Player player, int bagSlot, int pageIndex) {
        ItemStack bag = player.getInventory().getItem(bagSlot);
        if (bag == null || !seedBag.isSeedBag(bag)) {
            sendActionBar(player, "§cThat slot no longer holds a seed bag.");
            return;
        }
        SeedBagTier.Definition def = SeedBagTier.get(seedBag.getTier(bag));
        if (def == null) return;

        int page = Math.max(0, Math.min(pageIndex, def.pages() - 1));
        java.util.UUID instanceId = seedBag.getInstanceId(bag);
        SeedBagHolder holder = new SeedBagHolder(player, bagSlot, instanceId, page);
        String title = def.colorCode() + def.displayName()
                + (def.pages() > 1 ? " §8(Page §f" + (page + 1) + "§8/§f" + def.pages() + "§8)" : "");
        Inventory inv = Bukkit.createInventory(holder, SeedBagTier.GUI_SIZE, title);
        holder.setInventory(inv);

        renderPage(inv, bag, def, page, player);
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_BAMBOO_WOOD_DOOR_OPEN, 0.6f, 1.4f);
    }

    /** Re-render after upgrade or page change. */
    private void renderPage(Inventory inv, ItemStack bag, SeedBagTier.Definition def,
                            int page, Player owner) {
        // Storage area
        ItemStack[] pageContents = seedBag.readPage(bag, page);
        int unlocked = def.slotsOnPage(page);
        for (int i = 0; i < SeedBagTier.PAGE_SLOTS; i++) {
            if (i < unlocked) {
                inv.setItem(i, pageContents[i]);
            } else {
                inv.setItem(i, lockedPane(def.tier()));
            }
        }
        // Clear any leftover storage-area items the previous render may have placed
        // beyond the unlocked range on a tier downgrade (defensive — never happens
        // currently, since we don't downgrade).

        // Nav row
        for (int s = SeedBagTier.NAV_OFFSET; s < SeedBagTier.GUI_SIZE; s++) {
            inv.setItem(s, navFiller());
        }
        if (def.pages() > 1) {
            if (page > 0) {
                inv.setItem(PREV_PAGE_SLOT, navItem(Material.ARROW, "§e◀ Previous page",
                        "§7Page " + page));
            }
            if (page < def.pages() - 1) {
                inv.setItem(NEXT_PAGE_SLOT, navItem(Material.ARROW, "§eNext page ▶",
                        "§7Page " + (page + 2)));
            }
        }
        inv.setItem(DEPOSIT_ALL_SLOT, navItem(Material.HOPPER, "§a⤓ Deposit all seeds",
                "§7Sweeps every CropFarm seed from your",
                "§7inventory into this bag."));
        inv.setItem(CLOSE_SLOT, navItem(Material.BARRIER, "§c✖ Close"));
        inv.setItem(INFO_SLOT, buildInfoCard(bag, def));
        inv.setItem(UPGRADE_SLOT, buildUpgradeCard(owner, bag, def));
    }

    // ---------------------------------------------------------------
    // Click handling
    // ---------------------------------------------------------------

    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof SeedBagHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Cancel ALWAYS first — we'll selectively re-allow vanilla behaviour
        // for storage-slot deposits/withdraws after validating the item.
        event.setCancelled(true);

        // Block any click on the bag's own held slot in the player inventory
        // (so the bag itself can't be moved or destroyed mid-open). The
        // bagSlot is in the bottom inventory.
        if (event.getClickedInventory() == player.getInventory()
                && event.getSlot() == holder.getBagSlot()) {
            // Also block hotbar-swap into a number key that maps to bagSlot
            return;
        }
        // And block hotbar-swap that targets the bag slot from any click
        if (event.getClick() == ClickType.NUMBER_KEY
                && event.getHotbarButton() == holder.getBagSlot()) {
            return;
        }

        ItemStack bag = resolveBag(player, holder);
        if (bag == null) {
            // Bag was lost / replaced / cleared. Close to avoid desync.
            player.closeInventory();
            return;
        }
        SeedBagTier.Definition def = SeedBagTier.get(seedBag.getTier(bag));
        if (def == null) return;

        int rawSlot = event.getRawSlot();
        boolean inTop = rawSlot < SeedBagTier.GUI_SIZE;

        // ---- Nav row ----
        if (inTop && rawSlot >= SeedBagTier.NAV_OFFSET) {
            handleNavClick(player, holder, bag, def, rawSlot);
            return;
        }

        // ---- Locked storage slots ----
        if (inTop && rawSlot >= def.slotsOnPage(holder.getCurrentPage())) {
            sendActionBar(player, "§c🔒 Locked — upgrade your bag to use this slot.");
            return;
        }

        // ---- Storage area (top, unlocked) OR player inventory ----
        // Determine what item, if any, would end up inside the bag as a
        // result of this click. If non-null and not a CropFarm seed, reject.
        ItemStack movingIntoBag = itemEnteringBag(event, inTop);
        if (movingIntoBag != null && !isCropFarmSeed(movingIntoBag)) {
            sendActionBar(player, "§c⚠ This bag stores §fCropFarm seeds§c only.");
            return;
        }

        // For COLLECT_TO_CURSOR: when collecting matching items to cursor,
        // it pulls from BOTH inventories. If the player double-clicks on
        // a non-seed in the bottom inventory while a non-seed cursor would
        // gather more from the top, that's blocked by the seed-only invariant
        // (bag never contains non-seeds), so vanilla behaviour is safe to
        // re-allow.
        event.setCancelled(false);

        // After a successful storage-slot interaction, re-render the info /
        // upgrade cards next tick so the count + XP/ingredient display
        // updates without forcing the player to reopen.
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack live = resolveBag(player, holder);
            if (live == null) return;
            // Defensive eject: scan the storage area for any non-seed item
            // (creative paste, plugin shenanigans, race condition) and
            // bounce it back to the player's inventory.
            ejectNonSeeds(player, top, def, holder.getCurrentPage());
            // Persist the page state we just changed in-memory so totalStoredCount() reflects it.
            persistCurrentPage(top, live, def, holder.getCurrentPage());
            seedBag.refreshDisplay(live);
            top.setItem(INFO_SLOT,    buildInfoCard(live, def));
            top.setItem(UPGRADE_SLOT, buildUpgradeCard(player, live, def));
        });
    }

    /**
     * Look up the bag the player is currently viewing. Tries the captured
     * slot first; falls back to a full inventory scan matching on bag
     * instance UUID so an external plugin moving the bag mid-open doesn't
     * desync the GUI. Public so SeedBagListener can use the same lookup
     * during InventoryClose / drop events.
     */
    public ItemStack resolveBag(Player player, SeedBagHolder holder) {
        ItemStack atSlot = player.getInventory().getItem(holder.getBagSlot());
        if (atSlot != null && seedBag.isSeedBag(atSlot)
                && holder.getBagInstanceId() != null
                && holder.getBagInstanceId().equals(seedBag.getInstanceId(atSlot))) {
            return atSlot;
        }
        if (holder.getBagInstanceId() == null) return null;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (seedBag.isSeedBag(s)
                    && holder.getBagInstanceId().equals(seedBag.getInstanceId(s))) {
                return s;
            }
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (seedBag.isSeedBag(off)
                && holder.getBagInstanceId().equals(seedBag.getInstanceId(off))) {
            return off;
        }
        return null;
    }

    /**
     * Remove any non-CropFarm-seed items from the visible storage range and
     * push them back into the player's inventory (or drop at feet if full).
     */
    private void ejectNonSeeds(Player player, Inventory top,
                               SeedBagTier.Definition def, int page) {
        int unlocked = def.slotsOnPage(page);
        for (int i = 0; i < unlocked; i++) {
            ItemStack s = top.getItem(i);
            if (s == null || s.getType().isAir()) continue;
            if (isCropFarmSeed(s)) continue;
            top.setItem(i, null);
            var leftover = player.getInventory().addItem(s);
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
    }

    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof SeedBagHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack bag = resolveBag(player, holder);
        if (bag == null) {
            event.setCancelled(true);
            return;
        }
        SeedBagTier.Definition def = SeedBagTier.get(seedBag.getTier(bag));
        if (def == null) {
            event.setCancelled(true);
            return;
        }
        int unlocked = def.slotsOnPage(holder.getCurrentPage());

        // If any dragged-into slot is locked / nav, OR any dragged item is
        // not a CropFarm seed, cancel the whole drag. Drags must be
        // all-valid or none — partial drags would dump non-seed items into
        // the bag inventory.
        ItemStack dragged = event.getOldCursor();
        boolean dragMaterialIsSeed = isCropFarmSeed(dragged);
        for (int slot : event.getRawSlots()) {
            if (slot >= SeedBagTier.GUI_SIZE) continue; // bottom inv — fine
            if (slot >= SeedBagTier.NAV_OFFSET || slot >= unlocked) {
                event.setCancelled(true);
                sendActionBar(player, "§c⚠ Cannot drop items there.");
                return;
            }
            if (!dragMaterialIsSeed) {
                event.setCancelled(true);
                sendActionBar(player, "§c⚠ This bag stores §fCropFarm seeds§c only.");
                return;
            }
        }
        // Re-render after drag completes
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack live = resolveBag(player, holder);
            if (live == null) return;
            persistCurrentPage(top, live, def, holder.getCurrentPage());
            seedBag.refreshDisplay(live);
            top.setItem(INFO_SLOT,    buildInfoCard(live, def));
            top.setItem(UPGRADE_SLOT, buildUpgradeCard(player, live, def));
        });
    }

    /** Persist the GUI's storage area back into the bag's PDC for the page. */
    public void persistCurrentPage(Inventory top, ItemStack bag,
                                   SeedBagTier.Definition def, int page) {
        int unlocked = def.slotsOnPage(page);
        ItemStack[] contents = new ItemStack[unlocked];
        for (int i = 0; i < unlocked; i++) {
            ItemStack s = top.getItem(i);
            // Reject anything non-seed defensively (shouldn't happen given
            // click filter, but corruption-resistant).
            if (s != null && isCropFarmSeed(s)) {
                contents[i] = s;
            }
        }
        seedBag.writePage(bag, page, contents);
    }

    // ---------------------------------------------------------------
    // Nav-row actions
    // ---------------------------------------------------------------

    private void handleNavClick(Player player, SeedBagHolder holder,
                                ItemStack bag, SeedBagTier.Definition def, int rawSlot) {
        if (rawSlot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (rawSlot == DEPOSIT_ALL_SLOT) {
            doDepositAll(player, holder, bag, def);
            return;
        }
        if (rawSlot == PREV_PAGE_SLOT && holder.getCurrentPage() > 0) {
            persistCurrentPage(holder.getInventory(), bag, def, holder.getCurrentPage());
            int newPage = holder.getCurrentPage() - 1;
            holder.setCurrentPage(newPage);
            renderPage(holder.getInventory(), bag, def, newPage, player);
            playClick(player);
            return;
        }
        if (rawSlot == NEXT_PAGE_SLOT && holder.getCurrentPage() < def.pages() - 1) {
            persistCurrentPage(holder.getInventory(), bag, def, holder.getCurrentPage());
            int newPage = holder.getCurrentPage() + 1;
            holder.setCurrentPage(newPage);
            renderPage(holder.getInventory(), bag, def, newPage, player);
            playClick(player);
            return;
        }
        if (rawSlot == UPGRADE_SLOT) {
            doUpgrade(player, holder, bag, def);
            return;
        }
        // INFO_SLOT and fillers: no action
    }

    private void doDepositAll(Player player, SeedBagHolder holder,
                              ItemStack bag, SeedBagTier.Definition def) {
        // Persist current page first so we don't overwrite in-flight changes.
        persistCurrentPage(holder.getInventory(), bag, def, holder.getCurrentPage());

        PlayerInventory pinv = player.getInventory();
        int absorbed = 0;
        // Walk every page, gather available slots, then suck seeds into them.
        for (int p = 0; p < def.pages() && hasAnyCropFarmSeed(pinv); p++) {
            ItemStack[] page = seedBag.readPage(bag, p);
            int unlocked = def.slotsOnPage(p);
            boolean changed = false;
            for (int i = 0; i < unlocked && hasAnyCropFarmSeed(pinv); i++) {
                ItemStack target = page[i];
                if (target == null || target.getType().isAir()) {
                    // Empty slot — pull next seed stack
                    int srcSlot = findCropFarmSeedSlot(pinv, holder.getBagSlot(), null);
                    if (srcSlot < 0) break;
                    ItemStack src = pinv.getItem(srcSlot);
                    page[i] = src.clone();
                    pinv.setItem(srcSlot, null);
                    absorbed += page[i].getAmount();
                    changed = true;
                } else {
                    // Existing stack — try to top up from a matching source
                    int max = target.getMaxStackSize();
                    if (target.getAmount() >= max) continue;
                    int srcSlot = findCropFarmSeedSlot(pinv, holder.getBagSlot(), target);
                    if (srcSlot < 0) continue;
                    ItemStack src = pinv.getItem(srcSlot);
                    int room = max - target.getAmount();
                    int move = Math.min(room, src.getAmount());
                    target.setAmount(target.getAmount() + move);
                    src.setAmount(src.getAmount() - move);
                    if (src.getAmount() <= 0) pinv.setItem(srcSlot, null);
                    absorbed += move;
                    changed = true;
                    i--; // re-check this slot in case it can absorb more
                }
            }
            if (changed) seedBag.writePage(bag, p, page);
        }

        if (absorbed > 0) {
            seedBag.refreshDisplay(bag);
            renderPage(holder.getInventory(), bag, def, holder.getCurrentPage(), player);
            sendActionBar(player, "§a⤓ Deposited §f" + absorbed + "§a seeds.");
            player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.7f, 1.2f);
        } else {
            sendActionBar(player, "§7No seeds to deposit (or bag full).");
        }
    }

    private void doUpgrade(Player player, SeedBagHolder holder,
                           ItemStack bag, SeedBagTier.Definition def) {
        if (def.isTerminal()) {
            sendActionBar(player, "§5✦ Already at the terminal tier.");
            return;
        }
        SeedBagTier.Definition next = SeedBagTier.next(def.tier());
        if (next == null) return;

        // Persist current page so upgrade includes the latest contents.
        persistCurrentPage(holder.getInventory(), bag, def, holder.getCurrentPage());

        // XP gate
        int xp = seedBag.getXp(bag);
        if (xp < def.xpToUpgrade()) {
            sendActionBar(player, "§c⚠ Need " + (def.xpToUpgrade() - xp) + " more XP to upgrade.");
            return;
        }
        // Ingredient gate
        UpgradeCheck check = checkUpgradeRequirements(player, bag, def);
        if (!check.ok) {
            sendActionBar(player, "§c⚠ Missing: §f" + check.firstMissing);
            return;
        }

        // Atomicity: flip the tier BEFORE consuming ingredients. If anything
        // about the per-page PDC writes throws (BukkitObjectOutputStream
        // serialise failure — extremely rare but possible), the player keeps
        // any un-consumed ingredients and still has the upgraded bag. The
        // alternative (consume first) risks items lost with no benefit.
        seedBag.setTier(bag, next.tier());
        try {
            consumeUpgradeIngredients(player, bag, def);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Bag upgrade ingredient consumption failed for "
                    + player.getName() + ": " + e.getMessage());
        }

        // Re-open at page 0 with the new tier. The flag stops the old
        // inventory's onClose from writing the pre-consume page back into
        // the bag's PDC (which would undo our consumption). Re-ordering: the
        // deferred open() task intentionally runs AFTER any pending click-
        // handler runTasks queued earlier this tick (FIFO).
        holder.setSuppressCloseSave(true);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        sendActionBar(player, "§a✦ Upgraded to §f" + next.displayName() + "§a!");
        player.getInventory().setItem(holder.getBagSlot(), bag);
        Bukkit.getScheduler().runTask(plugin, () -> open(player, holder.getBagSlot(), 0));
    }

    // ---------------------------------------------------------------
    // Upgrade ingredient checking / consumption
    // ---------------------------------------------------------------

    record UpgradeCheck(boolean ok, String firstMissing, List<String> details) { }

    /**
     * Evaluate whether the player has the ingredients to upgrade. Pulls
     * vanilla ingredients only from the player inventory, but seed quotas
     * (any-of-tier and exemplar) draw from BOTH player inventory and the
     * bag's stored seeds.
     */
    public UpgradeCheck checkUpgradeRequirements(Player player, ItemStack bag,
                                                 SeedBagTier.Definition def) {
        List<String> details = new ArrayList<>();
        String firstMissing = null;
        boolean ok = true;
        for (SeedBagTier.Ingredient req : def.upgradeIngredients()) {
            String line;
            boolean satisfied;
            if (req.vanilla() != null) {
                int have = countVanillaInInventory(player, req.vanilla());
                satisfied = have >= req.amount();
                line = (satisfied ? "§a✓ " : "§c✗ ") + have + "§7/§f" + req.amount() + " "
                        + humanize(req.vanilla().name());
            } else if (req.exemplar()) {
                Set<String> needed = cropTypeIdsInBand(req.countCropTier());
                Set<String> haveTypes = cropTypeIdsAvailable(player, bag, req.countCropTier());
                Set<String> missing = new HashSet<>(needed);
                missing.removeAll(haveTypes);
                satisfied = missing.isEmpty() && !needed.isEmpty();
                if (needed.isEmpty()) {
                    line = "§7(no " + req.countCropTier().configId() + " crops registered)";
                    satisfied = true; // vacuously
                } else {
                    line = (satisfied ? "§a✓ " : "§c✗ ") + (needed.size() - missing.size())
                            + "§7/§f" + needed.size() + " §7unique "
                            + req.countCropTier().configId() + " seeds";
                }
            } else {
                int have = countSeedsInBand(player, bag, req.countCropTier());
                satisfied = have >= req.amount();
                line = (satisfied ? "§a✓ " : "§c✗ ") + have + "§7/§f" + req.amount() + " §7"
                        + req.countCropTier().configId() + " seeds";
            }
            details.add(line);
            if (!satisfied && firstMissing == null) {
                firstMissing = stripColor(line.substring(line.indexOf(' ') + 1));
                ok = false;
            }
        }
        return new UpgradeCheck(ok, firstMissing, details);
    }

    private void consumeUpgradeIngredients(Player player, ItemStack bag,
                                           SeedBagTier.Definition def) {
        for (SeedBagTier.Ingredient req : def.upgradeIngredients()) {
            if (req.vanilla() != null) {
                consumeVanillaFromInventory(player, req.vanilla(), req.amount());
            } else if (req.exemplar()) {
                // Take exactly one of every type in the band; consume from
                // player inventory first, then bag.
                for (String cropId : cropTypeIdsInBand(req.countCropTier())) {
                    if (!consumeOneSeedOfType(player, bag, cropId)) {
                        // This shouldn't happen because we already validated;
                        // log loudly so the bug is visible.
                        plugin.getLogger().warning("Upgrade consumption: missing exemplar "
                                + cropId + " for player " + player.getName());
                    }
                }
            } else {
                consumeSeedsInBand(player, bag, req.countCropTier(), req.amount());
            }
        }
    }

    // ---------------------------------------------------------------
    // Item filters / counting
    // ---------------------------------------------------------------

    private boolean isCropFarmSeed(ItemStack stack) {
        if (stack == null) return false;
        return plugin.getCropManager().getCropTypeFromSeed(stack) != null;
    }

    private boolean hasAnyCropFarmSeed(PlayerInventory pinv) {
        for (ItemStack s : pinv.getStorageContents()) {
            if (isCropFarmSeed(s)) return true;
        }
        return false;
    }

    /**
     * Return the first inventory slot containing a CropFarm seed (excluding
     * the held bag slot). When `mustMatch` is non-null, only matching stacks
     * are returned (so deposit-all can stack-merge before consuming new slots).
     */
    private int findCropFarmSeedSlot(PlayerInventory pinv, int excludeSlot, ItemStack mustMatch) {
        ItemStack[] contents = pinv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (i == excludeSlot) continue;
            ItemStack s = contents[i];
            if (!isCropFarmSeed(s)) continue;
            if (mustMatch != null && !mustMatch.isSimilar(s)) continue;
            return i;
        }
        return -1;
    }

    private int countVanillaInInventory(Player player, Material mat) {
        int total = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s != null && s.getType() == mat) total += s.getAmount();
        }
        return total;
    }

    private void consumeVanillaFromInventory(Player player, Material mat, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType() != mat) continue;
            int take = Math.min(remaining, s.getAmount());
            s.setAmount(s.getAmount() - take);
            if (s.getAmount() <= 0) contents[i] = null;
            remaining -= take;
        }
        player.getInventory().setStorageContents(contents);
    }

    private int countSeedsInBand(Player player, ItemStack bag, SeedBagTier.CropTierBand band) {
        int total = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (matchesBand(s, band)) total += s.getAmount();
        }
        for (ItemStack s : seedBag.allStoredSeeds(bag)) {
            if (matchesBand(s, band)) total += s.getAmount();
        }
        return total;
    }

    private void consumeSeedsInBand(Player player, ItemStack bag,
                                    SeedBagTier.CropTierBand band, int amount) {
        int remaining = amount;
        // Player inventory first
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack s = contents[i];
            if (!matchesBand(s, band)) continue;
            int take = Math.min(remaining, s.getAmount());
            s.setAmount(s.getAmount() - take);
            if (s.getAmount() <= 0) contents[i] = null;
            remaining -= take;
        }
        player.getInventory().setStorageContents(contents);

        if (remaining <= 0) return;

        // Then bag pages — mutate each page's contents and write back.
        SeedBagTier.Definition def = SeedBagTier.get(seedBag.getTier(bag));
        if (def == null) return;
        for (int p = 0; p < def.pages() && remaining > 0; p++) {
            ItemStack[] page = seedBag.readPage(bag, p);
            boolean changed = false;
            for (int i = 0; i < page.length && remaining > 0; i++) {
                ItemStack s = page[i];
                if (!matchesBand(s, band)) continue;
                int take = Math.min(remaining, s.getAmount());
                s.setAmount(s.getAmount() - take);
                if (s.getAmount() <= 0) page[i] = null;
                remaining -= take;
                changed = true;
            }
            if (changed) seedBag.writePage(bag, p, page);
        }
    }

    private boolean consumeOneSeedOfType(Player player, ItemStack bag, String cropId) {
        // Player inventory first
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            CropType t = plugin.getCropManager().getCropTypeFromSeed(s);
            if (t != null && cropId.equals(t.getId())) {
                s.setAmount(s.getAmount() - 1);
                if (s.getAmount() <= 0) contents[i] = null;
                player.getInventory().setStorageContents(contents);
                return true;
            }
        }
        // Bag
        SeedBagTier.Definition def = SeedBagTier.get(seedBag.getTier(bag));
        if (def == null) return false;
        for (int p = 0; p < def.pages(); p++) {
            ItemStack[] page = seedBag.readPage(bag, p);
            for (int i = 0; i < page.length; i++) {
                ItemStack s = page[i];
                CropType t = plugin.getCropManager().getCropTypeFromSeed(s);
                if (t != null && cropId.equals(t.getId())) {
                    s.setAmount(s.getAmount() - 1);
                    if (s.getAmount() <= 0) page[i] = null;
                    seedBag.writePage(bag, p, page);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesBand(ItemStack stack, SeedBagTier.CropTierBand band) {
        CropType t = plugin.getCropManager().getCropTypeFromSeed(stack);
        if (t == null) return false;
        return band.configId().equalsIgnoreCase(t.getTier());
    }

    private Set<String> cropTypeIdsInBand(SeedBagTier.CropTierBand band) {
        Set<String> ids = new HashSet<>();
        for (CropType t : plugin.getCropManager().getCropTypes()) {
            if (band.configId().equalsIgnoreCase(t.getTier())) ids.add(t.getId());
        }
        return ids;
    }

    private Set<String> cropTypeIdsAvailable(Player player, ItemStack bag,
                                             SeedBagTier.CropTierBand band) {
        Set<String> have = new HashSet<>();
        for (ItemStack s : player.getInventory().getStorageContents()) {
            CropType t = plugin.getCropManager().getCropTypeFromSeed(s);
            if (t != null && band.configId().equalsIgnoreCase(t.getTier())) have.add(t.getId());
        }
        for (ItemStack s : seedBag.allStoredSeeds(bag)) {
            CropType t = plugin.getCropManager().getCropTypeFromSeed(s);
            if (t != null && band.configId().equalsIgnoreCase(t.getTier())) have.add(t.getId());
        }
        return have;
    }

    // ---------------------------------------------------------------
    // Click → "what would enter the bag"
    // ---------------------------------------------------------------

    /**
     * For a click event, return the item that would be deposited INTO the
     * bag inventory (top), or null if none. Used to decide whether to allow
     * the vanilla click logic.
     */
    private ItemStack itemEnteringBag(InventoryClickEvent event, boolean clickInTop) {
        InventoryAction action = event.getAction();
        ClickType click = event.getClick();
        switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR:
                if (clickInTop) return event.getCursor();
                return null;
            case MOVE_TO_OTHER_INVENTORY:
                if (!clickInTop) return event.getCurrentItem();
                return null;
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD:
                if (clickInTop) {
                    int hb = event.getHotbarButton();
                    if (hb >= 0) return event.getWhoClicked().getInventory().getItem(hb);
                }
                return null;
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------
    // Card builders
    // ---------------------------------------------------------------

    private ItemStack buildInfoCard(ItemStack bag, SeedBagTier.Definition def) {
        ItemStack card = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta m = card.getItemMeta();
        if (m == null) return card;
        m.setDisplayName(def.colorCode() + "✦ " + def.displayName());
        List<String> lore = new ArrayList<>();
        lore.add("§8Tier §f" + def.tier() + "§8/§f" + SeedBagTier.MAX_TIER);
        lore.add("§8Slots §f" + def.totalSlots() + " §8(§f" + def.pages() + "§8 page"
                + (def.pages() == 1 ? "" : "s") + ")");
        if (def.isTerminal()) {
            lore.add("§5§lTerminal tier — max storage.");
        } else {
            int xp = seedBag.getXp(bag);
            lore.add("§8XP §f" + xp + "§7/§f" + def.xpToUpgrade());
        }
        lore.add("");
        lore.add("§7Stores §fCropFarm seeds only§7.");
        lore.add("§7Earn XP by harvesting while");
        lore.add("§7this bag is in your inventory.");
        m.setLore(lore);
        card.setItemMeta(m);
        return card;
    }

    private ItemStack buildUpgradeCard(Player player, ItemStack bag, SeedBagTier.Definition def) {
        if (def.isTerminal()) {
            return navItem(Material.NETHER_STAR, "§5✦ Maxed",
                    "§7You've reached the highest tier.",
                    "§7Storage: §f" + def.totalSlots() + "§7 slots.");
        }
        SeedBagTier.Definition next = SeedBagTier.next(def.tier());
        if (next == null) return navFiller();
        UpgradeCheck check = checkUpgradeRequirements(player, bag, def);
        int xp = seedBag.getXp(bag);
        boolean xpOk = xp >= def.xpToUpgrade();
        boolean ready = xpOk && check.ok;

        Material icon = ready ? Material.EMERALD : Material.GUNPOWDER;
        String name = ready
                ? "§a✦ Upgrade to §f" + next.displayName() + " §a(click)"
                : "§e⤴ Upgrade to §f" + next.displayName();
        List<String> lore = new ArrayList<>();
        lore.add("§8New capacity: §f" + next.totalSlots() + " §8slots ("
                + next.pages() + " page" + (next.pages() == 1 ? "" : "s") + ")");
        lore.add("");
        lore.add((xpOk ? "§a✓ " : "§c✗ ") + xp + "§7/§f" + def.xpToUpgrade() + " §7Cultivation XP");
        lore.add("§8Ingredients (player inv §7+§8 bag):");
        for (String d : check.details) lore.add("  " + d);
        lore.add("");
        lore.add(ready ? "§eClick to upgrade." : "§7Gather what's missing, then click here.");
        return navItem(icon, name, lore.toArray(new String[0]));
    }

    private ItemStack lockedPane(int currentTier) {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = item.getItemMeta();
        if (m == null) return item;
        int unlockTier = unlockTierFor(currentTier);
        m.setDisplayName("§c🔒 Locked");
        List<String> lore = new ArrayList<>();
        if (unlockTier <= SeedBagTier.MAX_TIER) {
            SeedBagTier.Definition target = SeedBagTier.get(unlockTier);
            if (target != null) {
                lore.add("§7Unlocks at " + target.colorCode() + target.displayName() + "§7.");
            }
        }
        m.setLore(lore);
        item.setItemMeta(m);
        return item;
    }

    /**
     * For a current tier, the FIRST tier that exposes more page-0 slots than
     * this one — i.e. the tier the locked-preview slot will become available.
     */
    private static int unlockTierFor(int currentTier) {
        SeedBagTier.Definition cur = SeedBagTier.get(currentTier);
        if (cur == null) return currentTier + 1;
        int curUnlock = cur.slotsOnPage(0);
        for (int t = currentTier + 1; t <= SeedBagTier.MAX_TIER; t++) {
            SeedBagTier.Definition d = SeedBagTier.get(t);
            if (d != null && d.slotsOnPage(0) > curUnlock) return t;
        }
        return SeedBagTier.MAX_TIER;
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
        ItemStack it = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
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

    private static void sendActionBar(Player player, String legacyText) {
        player.sendActionBar(net.kyori.adventure.text.serializer.legacy
                .LegacyComponentSerializer.legacySection().deserialize(legacyText));
    }

    private static String stripColor(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

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

    /** Used by the command handler to surface upgrade requirements via chat. */
    public List<String> describeUpgradeRequirements(Player player, ItemStack bag) {
        if (!seedBag.isSeedBag(bag)) return Collections.emptyList();
        SeedBagTier.Definition def = SeedBagTier.get(seedBag.getTier(bag));
        if (def == null || def.isTerminal()) return Collections.emptyList();
        return checkUpgradeRequirements(player, bag, def).details;
    }
}
