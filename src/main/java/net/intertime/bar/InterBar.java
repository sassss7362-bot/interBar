package net.intertime.bar;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class InterBar extends JavaPlugin {

    private BarManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new BarManager(this);
        getServer().getPluginManager().registerEvents(manager, this);
        manager.start();
        getLogger().info("InterBar увімкнено!");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
            if (!s.hasPermission("intertime.bar.admin")) {
                s.sendMessage("Немає прав.");
                return true;
            }
            reloadConfig();
            manager.reload();
            s.sendMessage("InterBar: конфіг перезавантажено.");
            return true;
        }
        if (!(s instanceof Player p)) {
            s.sendMessage("Тільки для гравців.");
            return true;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("toggle")) {
            String key = a[1].toLowerCase();
            if (key.equals("sidebar") || key.equals("bossbar") || key.equals("tab")) {
                manager.toggle(p, key);
                BarManager.msg(p, "<gray>Панель <white>" + key + "<gray>: "
                        + (manager.isOn(p, key) ? "<green>увімкнено" : "<red>вимкнено"));
                return true;
            }
        }
        manager.openMenu(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        if (a.length == 1) {
            return List.of("toggle", "reload").stream().filter(x -> x.startsWith(a[0].toLowerCase())).toList();
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("toggle")) {
            return List.of("sidebar", "bossbar", "tab").stream().filter(x -> x.startsWith(a[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
