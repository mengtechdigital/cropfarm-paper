package com.cropfarm;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Routes /cropfarm, /cropfarmreload, /cropfarmgive.
 *
 * /cropfarm                       → open menu (player only)
 * /cropfarm menu [page]           → open menu
 * /cropfarm reload                → reload config (cropfarm.reload)
 * /cropfarm give <crop> [n] [p]   → give seeds (cropfarm.give)
 *
 * /cropfarmreload and /cropfarmgive are kept as direct shortcuts for
 * backwards compatibility with scripts and console use.
 */
public class CropFarmCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("menu", "reload", "give", "bag");
    private static final List<String> BAG_ACTIONS = List.of("help", "guide", "give", "info");

    private final CropFarm plugin;

    public CropFarmCommand(CropFarm plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        if (name.equals("cropfarmreload")) return doReload(sender);
        if (name.equals("cropfarmgive"))   return doGive(sender, args);

        // /cropfarm parent command
        if (args.length == 0) return doMenu(sender, new String[0]);
        String sub = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (sub) {
            case "menu"   -> doMenu(sender, rest);
            case "reload" -> doReload(sender);
            case "give"   -> doGive(sender, rest);
            case "bag"    -> doBag(sender, rest);
            default -> {
                sender.sendMessage("§cUnknown subcommand: " + sub);
                sender.sendMessage("§7Try: §f/cropfarm menu§7, §f/cropfarm bag§7, "
                        + "§f/cropfarm reload§7, §f/cropfarm give <crop>");
                yield true;
            }
        };
    }

    // ---------------------------------------------------------------
    // Subcommands
    // ---------------------------------------------------------------

    private boolean doMenu(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cMenu must be opened by a player.");
            return true;
        }
        if (!player.hasPermission("cropfarm.menu")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }
        // /cropfarm menu                  → main category menu
        // /cropfarm menu <category>       → category, page 1
        // /cropfarm menu <category> <pg>  → category, page N
        if (args.length == 0) {
            plugin.getCropMenu().openMainMenu(player);
            return true;
        }
        String category = args[0].toLowerCase();
        if (!plugin.getCropMenu().categoryExists(category)) {
            sender.sendMessage("§cUnknown category: " + category);
            sender.sendMessage("§7Tab-complete /cropfarm menu to see all categories.");
            return true;
        }
        int page = 0;
        if (args.length >= 2) {
            try {
                page = Math.max(0, Integer.parseInt(args[1]) - 1);
            } catch (NumberFormatException ignored) { /* default to first page */ }
        }
        plugin.getCropMenu().openCategory(player, category, page);
        return true;
    }

    private boolean doReload(CommandSender sender) {
        if (!sender.hasPermission("cropfarm.reload")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }
        plugin.getCropManager().reload();
        sender.sendMessage("§aCropFarm config reloaded — "
                + plugin.getCropManager().getCropTypes().size() + " crops loaded.");
        return true;
    }

    private boolean doGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cropfarm.give")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /cropfarm give <crop> [amount] [player]");
            return true;
        }

        CropType type = plugin.getCropManager().getCropType(args[0].toLowerCase());
        if (type == null) {
            sender.sendMessage("§cUnknown crop: " + args[0]);
            return true;
        }

        int amount = 1;
        if (args.length >= 2) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid amount: " + args[1]);
                return true;
            }
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + args[2]);
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§cConsole must specify a player.");
            return true;
        }

        ItemStack seed = plugin.getCropManager().createSeed(type, amount);
        var leftover = target.getInventory().addItem(seed);
        int delivered = amount;
        if (!leftover.isEmpty()) {
            int lost = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            delivered = amount - lost;
            sender.sendMessage("§e⚠ " + target.getName() + "'s inventory was full — only "
                    + delivered + "/" + amount + " delivered.");
        } else {
            sender.sendMessage("§aGave §f" + amount + "x " + type.getDisplayName()
                    + " §ato " + target.getName());
        }
        return true;
    }

    // ---------------------------------------------------------------
    // /cropfarm bag — guide, give, info
    // ---------------------------------------------------------------

    private boolean doBag(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return sendBagGuide(sender);
        }
        String action = args[0].toLowerCase();
        return switch (action) {
            case "help", "guide" -> sendBagGuide(sender);
            case "give"          -> doBagGive(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
            case "info"          -> doBagInfo(sender);
            default -> {
                sender.sendMessage("§cUnknown bag action: " + action);
                sender.sendMessage("§7Try: §f/cropfarm bag help§7, §f/cropfarm bag info§7, "
                        + "§f/cropfarm bag give [tier] [player]");
                yield true;
            }
        };
    }

    /**
     * Print the seed-bag guide. This is the user-facing docs surface — XP
     * curve, ingredient model, where stuff lives. Keep it scannable; players
     * read this once when they first hear about the bag.
     */
    private boolean sendBagGuide(CommandSender sender) {
        sender.sendMessage("§8§m─────────── §6Seed Bag §8§m───────────");
        sender.sendMessage("§7A portable, seeds-only stash with §f6 upgrade tiers§7.");
        sender.sendMessage("§7Crafting: §f3x3 = String/Leather corners, Wheat sides, Bundle center§7.");
        sender.sendMessage("§7Open: §fright-click§7 the bag in your hand.");
        sender.sendMessage("§7Earn §fCultivation XP§7 by harvesting while it's in your inventory.");
        sender.sendMessage("§7Upgrade: §fclick the upgrade slot inside the bag§7 once XP + ingredients are met.");
        sender.sendMessage("");
        sender.sendMessage("§8§lTier ladder:");
        for (SeedBagTier.Definition d : SeedBagTier.all()) {
            String pages = d.pages() + " page" + (d.pages() == 1 ? "" : "s");
            String xp = d.isTerminal()
                    ? "§5terminal"
                    : "§7" + d.xpToUpgrade() + " XP to upgrade";
            sender.sendMessage("  " + d.colorCode() + "T" + d.tier() + " "
                    + d.displayName() + " §8— §f" + d.totalSlots() + " slots §8(" + pages + ") §8— "
                    + xp);
        }
        sender.sendMessage("");
        sender.sendMessage("§7XP per harvest: §fcommon=1, uncommon=1, rare=2, epic=4, "
                + "legendary=8, mythic=16§7.");
        sender.sendMessage("§7Higher tiers require §fexemplar§7 ingredients — at least one of "
                + "every crop in a tier band — turning the bag into a Pokédex.");
        sender.sendMessage("§8§m──────────────────────────────");
        return true;
    }

    private boolean doBagGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("cropfarm.bag.give")) {
            sender.sendMessage("§cYou don't have permission.");
            return true;
        }
        int tier = 1;
        if (args.length >= 1) {
            try {
                tier = Math.max(1, Math.min(SeedBagTier.MAX_TIER, Integer.parseInt(args[0])));
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid tier: " + args[0]);
                return true;
            }
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + args[1]);
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§cConsole must specify a player.");
            return true;
        }

        ItemStack bag = plugin.getSeedBag().create(tier);
        var leftover = target.getInventory().addItem(bag);
        if (!leftover.isEmpty()) {
            sender.sendMessage("§e⚠ " + target.getName() + "'s inventory was full — bag dropped at their feet.");
            target.getWorld().dropItemNaturally(target.getLocation(), bag);
        } else {
            SeedBagTier.Definition def = SeedBagTier.get(tier);
            String name = def == null ? ("Tier " + tier) : def.displayName();
            sender.sendMessage("§aGave §f" + name + " §ato " + target.getName());
        }
        return true;
    }

    private boolean doBagInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use /cropfarm bag info.");
            return true;
        }
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (!plugin.getSeedBag().isSeedBag(inHand)) {
            sender.sendMessage("§7Hold a seed bag in your main hand to see its info.");
            return true;
        }
        SeedBagTier.Definition def = SeedBagTier.get(plugin.getSeedBag().getTier(inHand));
        if (def == null) return true;
        int xp = plugin.getSeedBag().getXp(inHand);
        int stored = plugin.getSeedBag().totalStoredCount(inHand);
        sender.sendMessage("§8§m──────── " + def.colorCode() + def.displayName() + " §8§m────────");
        sender.sendMessage("§7Tier §f" + def.tier() + "§7/§f" + SeedBagTier.MAX_TIER
                + " §8| §7" + def.pages() + " page" + (def.pages() == 1 ? "" : "s")
                + " §8| §7" + def.totalSlots() + " slots");
        sender.sendMessage("§7Stored: §f" + stored + " seeds");
        if (def.isTerminal()) {
            sender.sendMessage("§5Terminal tier — no further upgrade.");
        } else {
            sender.sendMessage("§7XP §f" + xp + "§7/§f" + def.xpToUpgrade()
                    + " §7toward next tier.");
            sender.sendMessage("§7Upgrade requirements:");
            for (String line : plugin.getSeedBagInventory().describeUpgradeRequirements(player, inHand)) {
                sender.sendMessage("  " + line);
            }
        }
        return true;
    }

    // ---------------------------------------------------------------
    // Tab completion
    // ---------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase();

        if (name.equals("cropfarm")) {
            if (args.length == 1) {
                return prefixFilter(SUBCOMMANDS, args[0]);
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
                return tabCompleteGive(Arrays.copyOfRange(args, 1, args.length));
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("menu")) {
                return prefixFilter(plugin.getCropMenu().listCategories(), args[1]);
            }
            if (args[0].equalsIgnoreCase("bag")) {
                if (args.length == 2) {
                    return prefixFilter(BAG_ACTIONS, args[1]);
                }
                if (args.length == 3 && args[1].equalsIgnoreCase("give")) {
                    List<String> tiers = new ArrayList<>();
                    for (int t = 1; t <= SeedBagTier.MAX_TIER; t++) tiers.add(String.valueOf(t));
                    return prefixFilter(tiers, args[2]);
                }
                if (args.length == 4 && args[1].equalsIgnoreCase("give")) {
                    List<String> names = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                    return prefixFilter(names, args[3]);
                }
            }
            return Collections.emptyList();
        }

        if (name.equals("cropfarmgive")) {
            return tabCompleteGive(args);
        }
        return Collections.emptyList();
    }

    private List<String> tabCompleteGive(String[] args) {
        if (args.length == 1) {
            return prefixFilter(plugin.getCropManager().getCropTypeIds(), args[0]);
        }
        if (args.length == 3) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return prefixFilter(names, args[2]);
        }
        return Collections.emptyList();
    }

    private static List<String> prefixFilter(Iterable<String> source, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String s : source) {
            if (s.toLowerCase().startsWith(p)) out.add(s);
        }
        return out;
    }
}
