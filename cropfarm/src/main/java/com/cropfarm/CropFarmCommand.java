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

    private static final List<String> SUBCOMMANDS = List.of("menu", "reload", "give");

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
            default -> {
                sender.sendMessage("§cUnknown subcommand: " + sub);
                sender.sendMessage("§7Try: §f/cropfarm menu§7, §f/cropfarm reload§7, §f/cropfarm give <crop>");
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
        int page = 0;
        if (args.length >= 1) {
            try {
                page = Math.max(0, Integer.parseInt(args[0]) - 1);
            } catch (NumberFormatException ignored) { /* default to first page */ }
        }
        plugin.getCropMenu().open(player, page);
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
