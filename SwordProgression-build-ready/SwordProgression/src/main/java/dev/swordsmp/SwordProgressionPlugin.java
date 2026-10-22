package dev.swordsmp;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class SwordProgressionPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<UUID, Profile> profiles = new HashMap<>();
    private final Map<UUID, BukkitTask> rolling = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<String, Long> victimCooldowns = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private NamespacedKey swordKey, ownerKey;

    private static final class Profile {
        SwordType sword; int energy;
        Profile(SwordType sword, int energy) { this.sword = sword; this.energy = energy; }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        swordKey = new NamespacedKey(this, "sword");
        ownerKey = new NamespacedKey(this, "owner");
        dataFile = new File(getDataFolder(), "players.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        for (String uuid : data.getKeys(false)) {
            try {
                SwordType type = SwordType.find(data.getString(uuid + ".sword", ""));
                if (type != null) profiles.put(UUID.fromString(uuid), new Profile(type, data.getInt(uuid + ".energy")));
            } catch (IllegalArgumentException ignored) { getLogger().warning("Invalid profile entry: " + uuid); }
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : List.of("sword", "energy")) {
            PluginCommand cmd = Objects.requireNonNull(getCommand(name));
            cmd.setExecutor(this); cmd.setTabCompleter(this);
        }
        for (Player p : Bukkit.getOnlinePlayers()) Bukkit.getScheduler().runTaskLater(this, () -> join(p), 10L);
    }
    @Override public void onDisable() {
        for (BukkitTask task : rolling.values()) task.cancel();
        rolling.clear(); saveProfiles();
    }
    private void saveProfiles() {
        for (Map.Entry<UUID, Profile> entry : profiles.entrySet()) {
            String path = entry.getKey().toString();
            data.set(path + ".sword", entry.getValue().sword.id());
            data.set(path + ".energy", entry.getValue().energy);
        }
        try { data.save(dataFile); } catch (IOException e) { getLogger().severe("Could not save players.yml: " + e.getMessage()); }
    }
    private int stage(Profile p) {
        if (p.energy >= getConfig().getInt("progression.stage3-energy", 15)) return 3;
        if (p.energy >= getConfig().getInt("progression.stage2-energy", 5)) return 2;
        return 1;
    }
    private boolean isSword(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(swordKey, PersistentDataType.STRING);
    }
    private boolean isOwnedSword(ItemStack item, Player p) {
        if (!isSword(item)) return false;
        PersistentDataContainer tags = item.getItemMeta().getPersistentDataContainer();
        Profile profile = profiles.get(p.getUniqueId());
        return profile != null && p.getUniqueId().toString().equals(tags.get(ownerKey, PersistentDataType.STRING))
            && profile.sword.id().equals(tags.get(swordKey, PersistentDataType.STRING));
    }
    private ItemStack makeSword(Player p, Profile profile) {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + profile.sword.display);
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.STRING, profile.sword.id());
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
        meta.setLore(List.of(ChatColor.GRAY + "Owner: " + p.getName(),
            ChatColor.YELLOW + "Progression: " + stage(profile),
            ChatColor.AQUA + "Energy: " + profile.energy + "/" + getConfig().getInt("progression.stage3-energy", 15),
            ChatColor.GREEN + "Right click: " + profile.sword.first,
            (stage(profile) >= 2 ? ChatColor.GREEN : ChatColor.DARK_GRAY) + "Shift + right click: " + profile.sword.second,
            (stage(profile) >= 3 ? ChatColor.GREEN : ChatColor.DARK_GRAY) + "Shift + left click: " + profile.sword.ultimate));
        item.setItemMeta(meta); return item;
    }
    private void removeTaggedSwords(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) if (isSword(p.getInventory().getItem(i))) p.getInventory().setItem(i, null);
        if (isSword(p.getInventory().getItemInOffHand())) p.getInventory().setItemInOffHand(null);
    }
    private void giveSword(Player p) {
        Profile profile = profiles.get(p.getUniqueId());
        if (profile == null) return;
        removeTaggedSwords(p);
        HashMap<Integer, ItemStack> excess = p.getInventory().addItem(makeSword(p, profile));
        if (!excess.isEmpty()) p.sendMessage(ChatColor.RED + "Make one free inventory slot and use /sword give (OP) to restore your sword.");
    }
    private SwordType pickSword() {
        int total = 0;
        for (SwordType t : SwordType.values()) total += Math.max(0, getConfig().getInt("rolling.weights." + t.id(), 1));
        if (total <= 0) return SwordType.values()[ThreadLocalRandom.current().nextInt(SwordType.values().length)];
        int n = ThreadLocalRandom.current().nextInt(total);
        for (SwordType t : SwordType.values()) { n -= Math.max(0, getConfig().getInt("rolling.weights." + t.id(), 1)); if (n < 0) return t; }
        return SwordType.INFERNAL;
    }
    private void roll(Player p) {
        UUID id = p.getUniqueId();
        if (rolling.containsKey(id)) { p.sendMessage(ChatColor.RED + "Already rolling!"); return; }
        SwordType selected = pickSword();
        int duration = Math.max(20, getConfig().getInt("rolling.duration-ticks", 100));
        // Commit the result first so disconnects never allow free rerolls.
        profiles.put(id, new Profile(selected, 0)); saveProfiles(); removeTaggedSwords(p);
        final int[] tick = {0};
        final int[] next = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!p.isOnline()) { BukkitTask current = rolling.remove(id); if (current != null) current.cancel(); return; }
            if (tick[0] >= duration) {
                BukkitTask current = rolling.remove(id); if (current != null) current.cancel();
                p.sendTitle(ChatColor.GOLD + "YOU ROLLED", ChatColor.YELLOW + selected.display, 5, 55, 15);
                p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 90, .8, .8, .8, new Particle.DustOptions(selected.color, 1.7f));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                giveSword(p); return;
            }
            if (tick[0] >= next[0]) {
                SwordType shown = tick[0] > duration - 12 ? selected : SwordType.values()[ThreadLocalRandom.current().nextInt(SwordType.values().length)];
                p.sendTitle(ChatColor.YELLOW + "ROLLING...", ChatColor.WHITE + shown.display, 0, 10, 0);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, .5f, 1.3f);
                p.getWorld().spawnParticle(Particle.DUST, p.getEyeLocation(), 8, .4, .4, .4, new Particle.DustOptions(shown.color, 1.1f));
                double fraction = (double) tick[0] / duration;
                next[0] = tick[0] + (fraction > .8 ? 10 : fraction > .55 ? 5 : 2);
            }
            tick[0]++;
        }, 1L, 1L);
        rolling.put(id, task);
    }
    private void join(Player p) {
        if (!p.isOnline()) return;
        if (!profiles.containsKey(p.getUniqueId())) roll(p);
        else if (!rolling.containsKey(p.getUniqueId())) giveSword(p);
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTaskLater(this, () -> join(e.getPlayer()), 10L); }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        BukkitTask task = rolling.remove(e.getPlayer().getUniqueId()); if (task != null) task.cancel();
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isSword);
        Player dead = e.getEntity(); Player killer = dead.getKiller();
        if (killer == null || killer.equals(dead) || !profiles.containsKey(killer.getUniqueId())) return;
        String key = killer.getUniqueId() + ":" + dead.getUniqueId();
        long now = System.currentTimeMillis();
        if (victimCooldowns.getOrDefault(key, 0L) > now) return;
        victimCooldowns.put(key, now + getConfig().getLong("combat.same-victim-cooldown-seconds", 120) * 1000L);
        addEnergy(killer, 1);
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { Bukkit.getScheduler().runTaskLater(this, () -> giveSword(e.getPlayer()), 5L); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (isSword(e.getItemDrop().getItemStack())) e.setCancelled(true); }
    @EventHandler public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() != null && e.getClickedInventory().getType() != InventoryType.PLAYER && isSword(e.getCursor())) e.setCancelled(true);
        if (e.getClickedInventory() != null && e.getClickedInventory().getType() != InventoryType.PLAYER && isSword(e.getCurrentItem())) e.setCancelled(true);
        if (e.isShiftClick() && isSword(e.getCurrentItem()) && e.getView().getTopInventory().getType() != InventoryType.CRAFTING) e.setCancelled(true);
        if (e.getHotbarButton() >= 0 && e.getClickedInventory() != null && e.getClickedInventory().getType() != InventoryType.PLAYER && isSword(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()))) e.setCancelled(true);
    }
    @EventHandler public void onDrag(InventoryDragEvent e) {
        if (!isSword(e.getOldCursor())) return;
        int top = e.getView().getTopInventory().getSize();
        if (e.getRawSlots().stream().anyMatch(slot -> slot < top)) e.setCancelled(true);
    }
    private void addEnergy(Player p, int amount) {
        Profile profile = profiles.get(p.getUniqueId()); if (profile == null) return;
        int old = stage(profile);
        profile.energy = Math.max(0, profile.energy + amount);
        saveProfiles(); giveSword(p);
        if (stage(profile) > old) {
            p.sendTitle(ChatColor.GOLD + "PROGRESSION " + stage(profile), ChatColor.YELLOW + "Ability unlocked!", 10, 50, 15);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, .7f);
            p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 80, .8, 1, .8, new Particle.DustOptions(profile.sword.color, 1.4f));
        } else p.sendMessage(ChatColor.AQUA + "Sword energy: " + profile.energy);
    }
    @EventHandler public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isOwnedSword(p.getInventory().getItemInMainHand(), p) || rolling.containsKey(p.getUniqueId())) return;
        boolean sneak = p.isSneaking();
        int ability = sneak && (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) ? 3
            : sneak ? 2 : (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) ? 1 : 0;
        if (ability == 0) return;
        e.setCancelled(true);
        Profile profile = profiles.get(p.getUniqueId());
        if (stage(profile) < ability) { p.sendMessage(ChatColor.RED + "Unlock progression " + ability + " first!"); return; }
        String key = p.getUniqueId() + ":" + ability;
        long now = System.currentTimeMillis(), expiry = cooldowns.getOrDefault(key, 0L);
        if (expiry > now) { p.sendMessage(ChatColor.RED + "Cooldown: " + ((expiry - now + 999) / 1000) + "s"); return; }
        int seconds = getConfig().getInt("abilities." + (ability == 1 ? "ability1" : ability == 2 ? "ability2" : "ultimate") + "-cooldown-seconds", ability == 1 ? 12 : ability == 2 ? 22 : 55);
        cooldowns.put(key, now + seconds * 1000L);
        cast(p, profile.sword, ability);
    }
    private void cast(Player p, SwordType sword, int ability) {
        Location loc = p.getLocation().clone(); World world = p.getWorld();
        Vector forward = loc.getDirection().setY(0).normalize();
        if (!Double.isFinite(forward.getX())) forward = new Vector(0, 0, 1);
        final Vector direction = forward;
        String abilityName = ability == 1 ? sword.first : ability == 2 ? sword.second : sword.ultimate;
        p.sendMessage(ChatColor.GOLD + "✦ " + abilityName);
        p.playSound(loc, Sound.ENTITY_EVOKER_CAST_SPELL, 1, ability == 3 ? .6f : 1.4f);
        if (ability == 1 && (sword == SwordType.INFERNAL || sword == SwordType.THUNDER || sword == SwordType.SHADOW)) {
            Vector destination = direction.clone().multiply(sword == SwordType.SHADOW ? 6 : 1.7);
            if (sword == SwordType.SHADOW) {
                Location target = loc.clone().add(destination);
                if (target.getBlock().isPassable() && target.clone().add(0, 1, 0).getBlock().isPassable()) p.teleport(target);
            } else p.setVelocity(destination.setY(.25));
        }
        if (sword == SwordType.VITALITY && ability == 2) p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.REGENERATION, 120, 1));
        if (sword == SwordType.VITALITY && ability == 3) p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + 8));
        int pulses = ability == 3 ? 12 : ability == 2 ? 6 : 3;
        final int[] index = {0};
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!p.isOnline() || !p.isValid() || index[0] >= pulses) { holder[0].cancel(); return; }
            double radius = 1.4 + index[0] * (ability == 3 ? .48 : .3);
            Location center = p.getLocation().add(0, .4, 0);
            for (int angle = 0; angle < 360; angle += 12) {
                double rad = Math.toRadians(angle);
                Location point = center.clone().add(Math.cos(rad) * radius, Math.sin(rad * 3 + index[0]) * .35, Math.sin(rad) * radius);
                world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, new Particle.DustOptions(sword.color, ability == 3 ? 1.8f : 1.1f));
            }
            if (index[0] == 0 || ability == 3 && index[0] % 3 == 0) {
                for (Entity entity : world.getNearbyEntities(center, radius + 1, 3, radius + 1)) {
                    if (!(entity instanceof LivingEntity living) || living.equals(p)) continue;
                    if (living instanceof Player target && (rolling.containsKey(target.getUniqueId()) || !p.canSee(target))) continue;
                    if (sword == SwordType.VITALITY && living instanceof Player) continue;
                    living.damage(ability == 3 ? 5 : ability == 2 ? 4 : 3, p);
                    if (sword == SwordType.FROST) living.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 80, 1));
                    if (sword == SwordType.INFERNAL) living.setFireTicks(60);
                    if (sword == SwordType.THUNDER && ability >= 2) world.strikeLightningEffect(living.getLocation());
                    if (sword == SwordType.EARTH) living.setVelocity(living.getVelocity().add(new Vector(0, .35, 0)));
                    if (sword == SwordType.VITALITY) p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + 1));
                }
            }
            index[0]++;
        }, 0L, 4L);
    }
    private boolean admin(CommandSender sender) {
        if (!sender.hasPermission("swordprogression.admin")) { sender.sendMessage(ChatColor.RED + "OP only."); return false; }
        return true;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!admin(sender)) return true;
        if (command.getName().equalsIgnoreCase("energy")) {
            if (args.length < 2) return false;
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null || !profiles.containsKey(target.getUniqueId())) { sender.sendMessage("Player not online or has no sword."); return true; }
            Profile profile = profiles.get(target.getUniqueId());
            if (args[0].equalsIgnoreCase("reset")) { profile.energy = 0; saveProfiles(); giveSword(target); sender.sendMessage("Energy reset."); return true; }
            if (args.length < 3) return false;
            int amount;
            try { amount = Integer.parseInt(args[2]); if (amount < 0) throw new NumberFormatException(); }
            catch (NumberFormatException ex) { sender.sendMessage("Amount must be a non-negative integer."); return true; }
            if (args[0].equalsIgnoreCase("give")) addEnergy(target, amount);
            else if (args[0].equalsIgnoreCase("set")) { profile.energy = 0; addEnergy(target, amount); }
            else return false;
            sender.sendMessage("Energy updated for " + target.getName()); return true;
        }
        if (args.length == 0) return false;
        if (args[0].equalsIgnoreCase("list")) { sender.sendMessage("Swords: " + String.join(", ", Arrays.stream(SwordType.values()).map(SwordType::id).toList())); return true; }
        if (!(sender instanceof Player p)) { sender.sendMessage("This command requires a player."); return true; }
        if (args[0].equalsIgnoreCase("reroll")) { roll(p); return true; }
        if (args[0].equalsIgnoreCase("give")) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : p;
            if (target == null) { sender.sendMessage("Player not online."); return true; }
            giveSword(target); return true;
        }
        if (args.length < 2) return false;
        if (args[0].equalsIgnoreCase("change")) {
            SwordType type = SwordType.find(args[1]);
            if (type == null) { sender.sendMessage("Unknown sword. Use /sword list."); return true; }
            profiles.put(p.getUniqueId(), new Profile(type, 0)); saveProfiles(); giveSword(p);
            sender.sendMessage("Changed to " + type.display); return true;
        }
        if (args[0].equalsIgnoreCase("progression")) {
            Profile profile = profiles.get(p.getUniqueId()); if (profile == null) return true;
            int level;
            try { level = Integer.parseInt(args[1]); } catch (NumberFormatException ex) { return false; }
            if (level < 1 || level > 3) return false;
            profile.energy = level == 1 ? 0 : getConfig().getInt("progression.stage" + level + "-energy", level == 2 ? 5 : 15);
            saveProfiles(); giveSword(p); sender.sendMessage("Set progression " + level); return true;
        }
        return false;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("swordprogression.admin")) return List.of();
        List<String> options = new ArrayList<>();
        if (args.length == 1) options.addAll(command.getName().equalsIgnoreCase("energy") ? List.of("give", "set", "reset") : List.of("list", "reroll", "change", "progression", "give"));
        if (args.length == 2) {
            if (command.getName().equalsIgnoreCase("energy") || args[0].equalsIgnoreCase("give")) options.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            if (args[0].equalsIgnoreCase("change")) options.addAll(Arrays.stream(SwordType.values()).map(SwordType::id).toList());
            if (args[0].equalsIgnoreCase("progression")) options.addAll(List.of("1", "2", "3"));
        }
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))).toList();
    }
}
