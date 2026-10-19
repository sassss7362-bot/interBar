package net.intertime.bar;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class BarManager implements Listener {

    static final String PREFIX = "<gradient:#00e5ff:#7c4dff><bold>ɪɴᴛᴇʀᴛɪᴍᴇ</bold></gradient> <dark_gray>» ";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String[] KEYS = {"sidebar", "bossbar", "tab"};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    record Rank(String id, String perm, String prefix, String color) {
    }

    record Msg(String text, BossBar.Color color, BossBar.Overlay style) {
    }

    static class Gui implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final InterBar pl;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, String[]> last = new HashMap<>();
    private final Map<UUID, String> tabCache = new HashMap<>();
    private final Map<UUID, Integer> rankCache = new HashMap<>();
    private final Map<String, Set<UUID>> off = new HashMap<>();

    private final File dataFile;
    private BukkitTask task;
    private long ticks;
    private boolean errorLogged;
    private Method papi;

    // конфіг
    private int updateTicks;
    private boolean sidebarOn, bossOn, tabOn, sortRank;
    private int titleSpeed;
    private List<String> titleFrames, lines, header, footer;
    private List<Msg> msgs;
    private int interval;
    private List<Rank> ranks;

    public BarManager(InterBar pl) {
        this.pl = pl;
        this.dataFile = new File(pl.getDataFolder(), "data.yml");
        for (String k : KEYS) off.put(k, new HashSet<>());
        loadData();
        load();
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                        .getMethod("setPlaceholders", Player.class, String.class);
            } catch (Exception ignored) {
            }
        }
    }

    // ================================================================ конфіг / дані

    private void load() {
        var c = pl.getConfig();
        updateTicks = Math.max(2, c.getInt("update-ticks", 10));
        sidebarOn = c.getBoolean("sidebar.enabled", true);
        bossOn = c.getBoolean("bossbar.enabled", true);
        tabOn = c.getBoolean("tab.enabled", true);
        sortRank = c.getBoolean("tab.sort-by-rank", true);
        titleSpeed = Math.max(1, c.getInt("sidebar.title-speed", 3));
        titleFrames = c.getStringList("sidebar.title-frames");
        if (titleFrames.isEmpty()) titleFrames = List.of("<bold>InterTime");
        lines = new ArrayList<>(c.getStringList("sidebar.lines"));
        if (lines.size() > 15) lines = new ArrayList<>(lines.subList(0, 15));
        header = c.getStringList("tab.header");
        footer = c.getStringList("tab.footer");
        interval = Math.max(1, c.getInt("bossbar.interval", 10));

        msgs = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("bossbar.messages")) {
            msgs.add(new Msg(String.valueOf(m.get("text")),
                    en(BossBar.Color.class, String.valueOf(m.get("color")), BossBar.Color.BLUE),
                    en(BossBar.Overlay.class, String.valueOf(m.get("style")), BossBar.Overlay.PROGRESS)));
        }

        ranks = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("ranks")) {
            ranks.add(new Rank(String.valueOf(m.get("id")),
                    m.get("permission") == null ? "" : String.valueOf(m.get("permission")),
                    m.get("prefix") == null ? "" : String.valueOf(m.get("prefix")),
                    m.get("color") == null ? "white" : String.valueOf(m.get("color"))));
        }
        if (ranks.isEmpty()) ranks.add(new Rank("player", "", "<gray>", "gray"));
    }

    private static <E extends Enum<E>> E en(Class<E> c, String s, E def) {
        try {
            return Enum.valueOf(c, s.toUpperCase());
        } catch (Exception e) {
            return def;
        }
    }

    private void loadData() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String k : KEYS) {
            for (String s : y.getStringList(k + "-off")) {
                try {
                    off.get(k).add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (String k : KEYS) y.set(k + "-off", off.get(k).stream().map(UUID::toString).toList());
        try {
            pl.getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            pl.getLogger().warning("Не вдалось зберегти data.yml: " + ex.getMessage());
        }
    }

    // ================================================================ запуск / зупинка

    public void start() {
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, updateTicks);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        clearAll();
    }

    public void reload() {
        clearAll();
        load();
        start();
    }

    private void clearAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            BossBar b = bars.get(p.getUniqueId());
            if (b != null) p.hideBossBar(b);
            p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            p.playerListName(null);
            if (boards.containsKey(p.getUniqueId())) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boards.clear();
        bars.clear();
        last.clear();
        tabCache.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        boards.remove(id);
        BossBar b = bars.remove(id);
        if (b != null) p.hideBossBar(b);
        last.remove(id);
        tabCache.remove(id);
        for (Scoreboard sb : boards.values()) {
            Team t = sb.getEntryTeam(p.getName());
            if (t != null) t.removeEntry(p.getName());
        }
    }

    // ================================================================ головний цикл

    private void tick() {
        ticks++;
        rankCache.clear();
        for (Player p : Bukkit.getOnlinePlayers()) rankCache.put(p.getUniqueId(), rankOf(p));
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                sidebar(p);
                bossbar(p);
                tab(p);
                teams(p);
            } catch (Exception ex) {
                if (!errorLogged) {
                    errorLogged = true;
                    pl.getLogger().warning("Помилка оновлення для " + p.getName() + ": " + ex);
                }
            }
        }
    }

    private int rankOf(Player p) {
        for (int i = 0; i < ranks.size(); i++) {
            Rank r = ranks.get(i);
            if (r.perm().isEmpty() || p.hasPermission(r.perm())) return i;
        }
        return ranks.size() - 1;
    }

    // ================================================================ плейсхолдери

    private String ph(Player p, String s) {
        if (s.indexOf('%') >= 0) {
            long mins = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200L;
            double tps = Math.min(20.0, Bukkit.getTPS()[0]);
            s = s.replace("%player%", p.getName())
                    .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                    .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()))
                    .replace("%ping%", String.valueOf(p.getPing()))
                    .replace("%world%", p.getWorld().getName())
                    .replace("%time%", LocalTime.now().format(TIME))
                    .replace("%date%", LocalDate.now().format(DATE))
                    .replace("%tps%", String.format(Locale.US, "%.1f", tps))
                    .replace("%x%", String.valueOf(p.getLocation().getBlockX()))
                    .replace("%y%", String.valueOf(p.getLocation().getBlockY()))
                    .replace("%z%", String.valueOf(p.getLocation().getBlockZ()))
                    .replace("%rank%", ranks.get(rankCache.getOrDefault(p.getUniqueId(), 0)).id().toUpperCase())
                    .replace("%kills%", String.valueOf(p.getStatistic(Statistic.PLAYER_KILLS)))
                    .replace("%deaths%", String.valueOf(p.getStatistic(Statistic.DEATHS)))
                    .replace("%playtime%", (mins / 60) + "г " + (mins % 60) + "хв")
                    .replace("%health%", String.valueOf((int) Math.ceil(p.getHealth())));
            if (papi != null) {
                try {
                    s = (String) papi.invoke(null, p, s);
                } catch (Exception ignored) {
                }
            }
        }
        return s;
    }

    // ================================================================ скорборд справа

    private Scoreboard board(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = sb.registerNewObjective("ib", Criteria.DUMMY, Component.text("InterTime"));
            try {
                o.numberFormat(NumberFormat.blank());
            } catch (Throwable ignored) {
            }
            for (int i = 0; i < ranks.size(); i++) {
                Rank r = ranks.get(i);
                Team t = sb.registerNewTeam(String.format("%02d_%s", i, r.id()));
                t.prefix(MM.deserialize(r.prefix()));
                TextColor col = NamedTextColor.NAMES.value(r.color().toLowerCase());
                if (col instanceof NamedTextColor n) t.color(n);
            }
            boards.put(p.getUniqueId(), sb);
            last.remove(p.getUniqueId());
        }
        if (p.getScoreboard() != sb) p.setScoreboard(sb);
        return sb;
    }

    private void sidebar(Player p) {
        Scoreboard sb = board(p);
        Objective o = sb.getObjective("ib");
        if (o == null) return;
        if (!sidebarOn || !isOn(p, "sidebar")) {
            sb.clearSlot(DisplaySlot.SIDEBAR);
            return;
        }
        if (o.getDisplaySlot() != DisplaySlot.SIDEBAR) o.setDisplaySlot(DisplaySlot.SIDEBAR);

        String[] cache = last.computeIfAbsent(p.getUniqueId(), k -> new String[lines.size() + 1]);
        String title = ph(p, titleFrames.get((int) ((ticks / titleSpeed) % titleFrames.size())));
        if (!title.equals(cache[0])) {
            cache[0] = title;
            o.displayName(MM.deserialize(title));
        }
        int n = lines.size();
        for (int i = 0; i < n; i++) {
            String s = ph(p, lines.get(i));
            if (!s.equals(cache[i + 1])) {
                cache[i + 1] = s;
                Score sc = o.getScore("l" + i);
                sc.setScore(n - i);
                sc.customName(MM.deserialize(s));
            }
        }
    }

    // ================================================================ боссбар

    private void bossbar(Player p) {
        UUID id = p.getUniqueId();
        BossBar bar = bars.get(id);
        if (!bossOn || !isOn(p, "bossbar") || msgs.isEmpty()) {
            if (bar != null) {
                p.hideBossBar(bar);
                bars.remove(id);
            }
            return;
        }
        double elapsed = ticks * updateTicks / 20.0;
        int idx = (int) (elapsed / interval) % msgs.size();
        float prog = (float) (1.0 - (elapsed % interval) / interval);
        Msg m = msgs.get(idx);
        if (bar == null) {
            bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
            bars.put(id, bar);
            p.showBossBar(bar);
        }
        bar.name(MM.deserialize(ph(p, m.text())));
        bar.color(m.color());
        bar.overlay(m.style());
        bar.progress(Math.max(0f, Math.min(1f, prog)));
    }

    // ================================================================ TAB

    private void tab(Player p) {
        UUID id = p.getUniqueId();
        if (!tabOn || !isOn(p, "tab")) {
            if (!"off".equals(tabCache.get(id))) {
                tabCache.put(id, "off");
                p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
                p.playerListName(null);
            }
            return;
        }
        Rank r = ranks.get(rankCache.getOrDefault(id, 0));
        String h = ph(p, String.join("<newline>", header));
        String f = ph(p, String.join("<newline>", footer));
        String key = r.id() + "\u0000" + h + "\u0000" + f;
        if (key.equals(tabCache.get(id))) return;
        tabCache.put(id, key);

        TextColor col = NamedTextColor.NAMES.value(r.color().toLowerCase());
        Component name = MM.deserialize(r.prefix()).append(Component.text(p.getName(), col == null ? NamedTextColor.WHITE : col));
        p.playerListName(name);
        p.sendPlayerListHeaderAndFooter(MM.deserialize(h), MM.deserialize(f));
    }

    /** Команди (teams) на скорборді глядача: сортування в TAB і префікс над головою. */
    private void teams(Player viewer) {
        if (!tabOn || !sortRank) return;
        Scoreboard sb = boards.get(viewer.getUniqueId());
        if (sb == null) return;
        for (Player q : Bukkit.getOnlinePlayers()) {
            int idx = rankCache.getOrDefault(q.getUniqueId(), ranks.size() - 1);
            Team t = sb.getTeam(String.format("%02d_%s", idx, ranks.get(idx).id()));
            if (t == null || t.hasEntry(q.getName())) continue;
            Team old = sb.getEntryTeam(q.getName());
            if (old != null) old.removeEntry(q.getName());
            t.addEntry(q.getName());
        }
    }

    // ================================================================ перемикачі та меню

    public boolean isOn(Player p, String key) {
        return !off.get(key).contains(p.getUniqueId());
    }

    public void toggle(Player p, String key) {
        Set<UUID> set = off.get(key);
        if (!set.remove(p.getUniqueId())) set.add(p.getUniqueId());
        saveData();
    }

    static void msg(Player p, String s) {
        p.sendMessage(MM.deserialize("<!italic>" + PREFIX + s));
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(MM.deserialize("<!italic>" + name));
        if (lore.length > 0) {
            im.lore(Arrays.stream(lore).map(l -> MM.deserialize("<!italic>" + l)).toList());
        }
        it.setItemMeta(im);
        return it;
    }

    public void openMenu(Player p) {
        Gui g = new Gui();
        g.inv = Bukkit.createInventory(g, 27,
                MM.deserialize("<gradient:#00e5ff:#7c4dff><bold>ɪɴᴛᴇʀᴛɪᴍᴇ</bold></gradient> <dark_gray>• <white>Панелі"));
        draw(g.inv, p);
        p.openInventory(g.inv);
    }

    private void draw(Inventory inv, Player p) {
        ItemStack edge = item(Material.CYAN_STAINED_GLASS_PANE, " ");
        ItemStack dark = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, (i < 9 || i >= 18) ? edge : dark);
        String[] names = {"Скорборд справа", "Боссбар зверху", "TAB (список гравців)"};
        Material[] icons = {Material.OAK_SIGN, Material.DRAGON_HEAD, Material.NAME_TAG};
        int[] slots = {11, 13, 15};
        for (int i = 0; i < 3; i++) {
            boolean on = isOn(p, KEYS[i]);
            inv.setItem(slots[i], item(icons[i],
                    (on ? "<green>✔ " : "<red>✘ ") + names[i],
                    "<gray>Стан: " + (on ? "<green>увімкнено" : "<red>вимкнено"),
                    "<yellow>Натисни щоб " + (on ? "вимкнути" : "увімкнути")));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Gui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int s = e.getSlot();
        int idx = s == 11 ? 0 : s == 13 ? 1 : s == 15 ? 2 : -1;
        if (idx < 0) return;
        toggle(p, KEYS[idx]);
        draw(e.getView().getTopInventory(), p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }
}
