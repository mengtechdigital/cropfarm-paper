package com.cropfarm;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CropFarmCommand implements CommandExecutor, TabCompleter {

    private final CropFarm plugin;

    public CropFarmCommand(CropFarm plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        if (name.equals("cropfarmreload")) {
            if (!sender.hasPermission("cropfarm.reload")) {
                sender.sendMessage("§cYou don't have permission.");
                return true;
            }
            plugin.getCropManager().reload();
            sender.sendMessage("§aCropFarm config reloaded.");
            return true;
        }

        if (name.equals("cropfarmgive")) {
            if (!sender.hasPermission("cropfarm.give")) {
                sender.sendMessage("§cYou don't have permission.");
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage("§cUsage: /cropfarmgive <crop> [amount] [player]");
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
            target.getInventory().addItem(seed);
            sender.sendMessage("§aGave §f" + amount + "x " + type.getDisplayName() + " §ato " + target.getName());
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("cropfarmgive")) return Collections.emptyList();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (String id : plugin.getCropManager().getCropTypeIds()) {
                if (id.startsWith(prefix)) out.add(id);
            }
            return out;
        }

        if (args.length == 3) {
            String prefix = args[2].toLowerCase();
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) out.add(p.getName());
            }
            return out;
        }

        return Collections.emptyList();
    }
}
