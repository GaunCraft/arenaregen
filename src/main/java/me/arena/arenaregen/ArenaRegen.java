package me.arena.arenaregen;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ArenaRegen extends JavaPlugin implements Listener {

    /**
     * Hafif kayit: normal bloklar icin sadece BlockData (paylasilan, ucuz) tutulur.
     * BlockState snapshot'i yalnizca icerigi olan bloklar (sandik, tabela, spawner vb.) icin alinir.
     */
    private record Entry(int x, int y, int z, BlockData data, BlockState tile, long restoreAt) {}

    // Ekleme sirasi = sure sirasi (sabit gecikme), bu yuzden LinkedHashMap yeterli
    private final Map<Long, Entry> pending = new LinkedHashMap<>();
    private long tick = 0;
    private boolean fullWarned = false;

    private String worldName;
    private long delayTicks;
    private int perTick;
    private long budgetNanos;
    private int maxPending;
    private boolean noDrops, clearContainers;
    private boolean rExplosions, rBreak, rPlace, rFire, rFluids, rFalling;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::processQueue, 1L, 1L);
    }

    @Override
    public void onDisable() {
        restoreAll();
    }

    private void loadSettings() {
        FileConfiguration c = getConfig();
        worldName = c.getString("world", "arena");
        delayTicks = Math.max(1, c.getLong("delay-seconds", 120)) * 20L;
        perTick = Math.max(1, c.getInt("blocks-per-tick", 200));
        budgetNanos = (long) (Math.max(0.1, c.getDouble("max-ms-per-tick", 2.0)) * 1_000_000L);
        maxPending = Math.max(1000, c.getInt("max-pending", 300000));
        noDrops = c.getBoolean("no-drops", true);
        clearContainers = c.getBoolean("clear-containers-on-explosion", true);
        rExplosions = c.getBoolean("regen.explosions", true);
        rBreak = c.getBoolean("regen.player-break", true);
        rPlace = c.getBoolean("regen.player-place", true);
        rFire = c.getBoolean("regen.fire", true);
        rFluids = c.getBoolean("regen.fluids", true);
        rFalling = c.getBoolean("regen.falling-blocks", true);
    }

    // ---------- Kuyruk ----------

    private boolean isArena(Block b) {
        return b.getWorld().getName().equals(worldName);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    private boolean full() {
        if (pending.size() < maxPending) return false;
        if (!fullWarned) {
            fullWarned = true;
            getLogger().warning("Bekleyen blok siniri (" + maxPending + ") doldu, yeni degisiklikler kaydedilmeyecek.");
        }
        return true;
    }

    /** Canli bloktan kayit al. Zaten bekliyorsa hicbir nesne olusturmadan cikar. */
    private void schedule(Block b) {
        int x = b.getX(), y = b.getY(), z = b.getZ();
        long k = key(x, y, z);
        if (pending.containsKey(k) || full()) return;
        BlockState tile = b.getState(false) instanceof TileState ? b.getState() : null;
        pending.put(k, new Entry(x, y, z, b.getBlockData(), tile, tick + delayTicks));
    }

    /** Hazir state'ten kayit al (BlockPlaceEvent#getBlockReplacedState icin). */
    private void schedule(BlockState s) {
        long k = key(s.getX(), s.getY(), s.getZ());
        if (pending.containsKey(k) || full()) return;
        BlockState tile = s instanceof TileState ? s : null;
        pending.put(k, new Entry(s.getX(), s.getY(), s.getZ(), s.getBlockData(), tile, tick + delayTicks));
    }

    private void restore(World w, Entry e) {
        if (e.tile() != null) {
            e.tile().update(true, false);
            return;
        }
        Block b = w.getBlockAt(e.x(), e.y(), e.z());
        if (!b.getBlockData().equals(e.data())) { // zaten ayniysa dokunma
            b.setBlockData(e.data(), false);
        }
    }

    private void processQueue() {
        tick++;
        if (pending.isEmpty()) return;
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        long deadline = System.nanoTime() + budgetNanos;
        int done = 0;
        Iterator<Entry> it = pending.values().iterator();
        while (it.hasNext() && done < perTick) {
            Entry e = it.next();
            if (e.restoreAt() > tick) break;
            it.remove();
            restore(w, e);
            done++;
            // Zaman butcesi: tick basina en fazla max-ms-per-tick harca
            if ((done & 7) == 0 && System.nanoTime() > deadline) break;
        }
        if (pending.size() < maxPending) fullWarned = false;
    }

    private int restoreAll() {
        int n = pending.size();
        World w = Bukkit.getWorld(worldName);
        if (w != null) {
            for (Entry e : pending.values()) restore(w, e);
        }
        pending.clear();
        return n;
    }

    // ---------- Patlamalar ----------

    private void handleExplosion(List<Block> list) {
        List<Block> blocks = new ArrayList<>(list);
        blocks.sort(Comparator.comparingInt(Block::getY)); // alttan uste: once destek bloklar donsun
        for (Block b : blocks) {
            schedule(b);
            if (clearContainers && b.getState(false) instanceof Container c) {
                c.getInventory().clear();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!rExplosions) return;
        if (!e.getLocation().getWorld().getName().equals(worldName)) return;
        handleExplosion(e.blockList());
        if (noDrops) e.setYield(0f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!rExplosions || !isArena(e.getBlock())) return;
        handleExplosion(e.blockList());
        if (noDrops) e.setYield(0f);
    }

    // ---------- Oyuncu ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!rBreak || !isArena(e.getBlock())) return;
        schedule(e.getBlock());
        if (noDrops) e.setDropItems(false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!rPlace || !isArena(e.getBlock())) return;
        schedule(e.getBlockReplacedState());
    }

    // ---------- Ates ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (!rFire || !isArena(e.getBlock())) return;
        schedule(e.getBlock());
    }

    // ---------- Sivilar ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!rFluids || !isArena(e.getBlock())) return;
        schedule(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!rFluids || !isArena(e.getBlock())) return;
        schedule(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent e) {
        if (!rFluids) return;
        Block to = e.getToBlock();
        if (!isArena(to)) return;
        schedule(to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        if (!rFluids || !isArena(e.getBlock())) return;
        schedule(e.getBlock());
    }

    // ---------- Dusen bloklar / entity blok degisimi ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (!rFalling || !isArena(e.getBlock())) return;
        schedule(e.getBlock());
    }

    // ---------- Komut ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                loadSettings();
                s.sendMessage("§aArenaRegen ayarlari yenilendi. (dunya: " + worldName + ", sure: " + delayTicks / 20 + " sn)");
            }
            case "now" -> s.sendMessage("§a" + restoreAll() + " blok hemen geri yuklendi.");
            default -> s.sendMessage("§eBekleyen blok sayisi: §f" + pending.size()
                    + " §7| dunya: " + worldName + " | sure: " + delayTicks / 20 + " sn");
        }
        return true;
    }
}
