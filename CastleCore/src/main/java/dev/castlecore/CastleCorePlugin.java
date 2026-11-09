package dev.castlecore;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class CastleCorePlugin extends JavaPlugin implements Listener {
    // registeredCores retains every configured core, including eliminated teams.
    // cores contains only currently active objectives during a match.
    private final Map<String, Core> registeredCores = new HashMap<>();
    private final Map<String, Core> cores = new HashMap<>();
    private final Map<String, TeamSpawn> teamSpawns = new HashMap<>();
    private final Map<UUID, String> playerTeams = new HashMap<>();
    private final Set<String> eliminatedTeams = new HashSet<>();
    private boolean matchActive = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("core")).setExecutor(this);
        getLogger().info("CastleCore enabled. Loaded " + cores.size() + " team core(s).");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadData() {
        cores.clear();
        registeredCores.clear();
        playerTeams.clear();
        eliminatedTeams.clear();

        if (getConfig().isConfigurationSection("cores")) {
            for (String team : getConfig().getConfigurationSection("cores").getKeys(false)) {
                String path = "cores." + team + ".";
                String worldName = getConfig().getString(path + "world");
                if (worldName == null || Bukkit.getWorld(worldName) == null) continue;
                Core loadedCore = new Core(
                        team,
                        worldName,
                        getConfig().getInt(path + "x"),
                        getConfig().getInt(path + "y"),
                        getConfig().getInt(path + "z"),
                        getConfig().getString(path + "color", "RED")
                );
                registeredCores.put(team.toLowerCase(Locale.ROOT), loadedCore);
                if (!eliminatedTeams.contains(team.toLowerCase(Locale.ROOT))) {
                    cores.put(team.toLowerCase(Locale.ROOT), loadedCore);
                }
            }
        }

        teamSpawns.clear();
        if (getConfig().isConfigurationSection("team-spawns")) {
            for (String team : getConfig().getConfigurationSection("team-spawns").getKeys(false)) {
                String path = "team-spawns." + team + ".";
                String worldName = getConfig().getString(path + "world");
                World world = worldName == null ? null : Bukkit.getWorld(worldName);
                if (world == null) continue;
                teamSpawns.put(team.toLowerCase(Locale.ROOT), new TeamSpawn(
                        worldName,
                        getConfig().getDouble(path + "x"),
                        getConfig().getDouble(path + "y"),
                        getConfig().getDouble(path + "z"),
                        (float) getConfig().getDouble(path + "yaw"),
                        (float) getConfig().getDouble(path + "pitch")
                ));
            }
        }

        if (getConfig().isConfigurationSection("players")) {
            for (String uuidText : getConfig().getConfigurationSection("players").getKeys(false)) {
                try {
                    String team = getConfig().getString("players." + uuidText);
                    if (team != null) playerTeams.put(UUID.fromString(uuidText), team.toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    getLogger().warning("Skipping invalid player UUID in config: " + uuidText);
                }
            }
        }

        if (getConfig().isList("eliminated-teams")) {
            for (String team : getConfig().getStringList("eliminated-teams")) {
                eliminatedTeams.add(team.toLowerCase(Locale.ROOT));
            }
        }
        cores.clear();
        for (Map.Entry<String, Core> entry : registeredCores.entrySet()) {
            if (!eliminatedTeams.contains(entry.getKey())) cores.put(entry.getKey(), entry.getValue());
        }
        matchActive = getConfig().getBoolean("match-active", false);
    }

    private void saveData() {
        getConfig().set("cores", null);
        getConfig().set("players", null);
        getConfig().set("team-spawns", null);

        for (Map.Entry<String, Core> entry : registeredCores.entrySet()) {
            Core c = entry.getValue();
            String path = "cores." + entry.getKey() + ".";
            getConfig().set(path + "world", c.world());
            getConfig().set(path + "x", c.x());
            getConfig().set(path + "y", c.y());
            getConfig().set(path + "z", c.z());
            getConfig().set(path + "color", c.color());
        }

        for (Map.Entry<String, TeamSpawn> entry : teamSpawns.entrySet()) {
            TeamSpawn spawn = entry.getValue();
            String path = "team-spawns." + entry.getKey() + ".";
            getConfig().set(path + "world", spawn.world());
            getConfig().set(path + "x", spawn.x());
            getConfig().set(path + "y", spawn.y());
            getConfig().set(path + "z", spawn.z());
            getConfig().set(path + "yaw", spawn.yaw());
            getConfig().set(path + "pitch", spawn.pitch());
        }

        for (Map.Entry<UUID, String> entry : playerTeams.entrySet()) {
            getConfig().set("players." + entry.getKey(), entry.getValue());
        }
        getConfig().set("eliminated-teams", new ArrayList<>(eliminatedTeams));
        getConfig().set("match-active", matchActive);
        saveConfig();
    }

    @EventHandler(ignoreCancelled = true)
    public void onCoreBreak(BlockBreakEvent event) {
        // The coloured glass is only a visual marker; it is not the objective.
        for (Core core : registeredCores.values()) {
            if (event.getBlock().getType().name().endsWith("_STAINED_GLASS") && core.matchesGlass(event.getBlock())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(color("&cBreak the beacon to destroy this team's core."));
                return;
            }
        }
        Core found = null;
        for (Core core : cores.values()) {
            if (core.matches(event.getBlock())) {
                found = core;
                break;
            }
        }
        if (found == null) return;

        Player player = event.getPlayer();
        if (!matchActive) {
            event.setCancelled(true);
            player.sendMessage(color("&cThe match is not running. Beacon cores are protected."));
            return;
        }

        String attackerTeam = playerTeams.get(player.getUniqueId());
        if (attackerTeam == null) {
            event.setCancelled(true);
            player.sendMessage(color("&cJoin a team before attacking a castle core."));
            return;
        }
        if (eliminatedTeams.contains(attackerTeam)) {
            event.setCancelled(true);
            player.sendMessage(color("&cYour team has been eliminated."));
            return;
        }
        if (found.team().equalsIgnoreCase(attackerTeam)) {
            event.setCancelled(true);
            player.sendMessage(color("&cYou cannot destroy your own team's beacon!"));
            return;
        }

        // Remove the decorative glass without dropping it; the beacon break remains allowed.
        event.getBlock().getRelative(BlockFace.UP).setType(Material.AIR, false);

        // The break is allowed. The core is removed from the active objective list
        // and its team is permanently eliminated for this match.
        String key = found.team().toLowerCase(Locale.ROOT);
        cores.remove(key);
        eliminatedTeams.add(key);
        saveData();

        Bukkit.broadcastMessage(color("&6[CastleCore] &e" + player.getName()
                + " &7has destroyed &c" + found.team() + " Team's "
                + found.color().toLowerCase(Locale.ROOT) + " beacon core&7!"));
        Bukkit.broadcastMessage(color("&c" + found.team() + " Team has been eliminated!"));
        checkForWinner();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String team = playerTeams.get(event.getPlayer().getUniqueId());
        if (team != null) {
            String display = cores.containsKey(team) ? cores.get(team).team() : capitalize(team);
            if (eliminatedTeams.contains(team)) {
                event.getPlayer().sendMessage(color("&cYour team, " + display + ", has been eliminated."));
            } else if (matchActive) {
                event.getPlayer().sendMessage(color("&eYou are still assigned to " + display
                        + " Team. Team switching is disabled until the match ends."));
            }
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!matchActive) return;

        Player player = event.getPlayer();
        String team = playerTeams.get(player.getUniqueId());
        if (team == null || eliminatedTeams.contains(team) || !cores.containsKey(team)) return;

        TeamSpawn spawn = teamSpawns.get(team);
        if (spawn == null) return;

        World world = Bukkit.getWorld(spawn.world());
        if (world == null) return;

        // Pick one of the nine block positions in a 3x3 area around the saved point.
        Location chosen = findSafeSpawn(world, spawn);
        if (chosen != null) event.setRespawnLocation(chosen);
    }

    private Location findSafeSpawn(World world, TeamSpawn spawn) {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) offsets.add(new int[]{dx, dz});
        }
        Collections.shuffle(offsets);

        int baseX = Location.locToBlock(spawn.x());
        int baseY = Location.locToBlock(spawn.y());
        int baseZ = Location.locToBlock(spawn.z());

        for (int[] offset : offsets) {
            int x = baseX + offset[0];
            int z = baseZ + offset[1];
            Block feet = world.getBlockAt(x, baseY, z);
            Block head = world.getBlockAt(x, baseY + 1, z);
            Block floor = world.getBlockAt(x, baseY - 1, z);
            if (feet.isPassable() && head.isPassable() && !floor.isPassable()) {
                return new Location(world, x + 0.5, baseY, z + 0.5, spawn.yaw(), spawn.pitch());
            }
        }

        // If the 3x3 area has no safe position, use the exact saved point as a fallback.
        Location fallback = new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
        Block feet = fallback.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);
        return feet.isPassable() && head.isPassable() && !floor.isPassable() ? fallback : null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("join")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can join a team.");
                return true;
            }
            if (!matchActive) {
                player.sendMessage(color("&cThere is no active match. Wait for an admin to run /core start."));
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(color("&cUsage: /core join <team>"));
                return true;
            }
            if (playerTeams.containsKey(player.getUniqueId())) {
                String current = playerTeams.get(player.getUniqueId());
                player.sendMessage(color("&cYou have already joined " + capitalize(current)
                        + " Team. You cannot switch teams during this match."));
                return true;
            }
            String team = args[1].toLowerCase(Locale.ROOT);
            if (!cores.containsKey(team)) {
                player.sendMessage(color("&cThat team has no active registered core."));
                return true;
            }
            if (eliminatedTeams.contains(team)) {
                player.sendMessage(color("&cThat team has already been eliminated."));
                return true;
            }
            playerTeams.put(player.getUniqueId(), team);
            saveData();
            player.sendMessage(color("&aYou joined &e" + cores.get(team).team()
                    + " Team&a. Your team is locked until this match ends."));
            Bukkit.broadcastMessage(color("&7[CastleCore] &e" + player.getName()
                    + " &7joined &e" + cores.get(team).team() + " Team&7."));
            return true;
        }

        if (sub.equals("leave")) {
            sender.sendMessage(color("&cYou cannot leave or switch teams during a match."));
            return true;
        }

        if (sub.equals("list")) {
            if (cores.isEmpty()) {
                sender.sendMessage(color("&eNo active castle cores are registered."));
            } else {
                sender.sendMessage(color("&6Active castle cores:"));
                for (Core core : cores.values()) {
                    sender.sendMessage(color("&e" + core.team() + " &7- "
                            + core.color().toLowerCase(Locale.ROOT) + " beacon at "
                            + core.world() + " " + core.x() + ", " + core.y() + ", " + core.z()));
                }
            }
            return true;
        }

        if (sub.equals("status")) {
            sender.sendMessage(color("&6[CastleCore] &7Match: "
                    + (matchActive ? "&aACTIVE" : "&cNOT ACTIVE")));
            sender.sendMessage(color("&7Registered active cores: &e" + cores.size()));
            sender.sendMessage(color("&7Eliminated teams: &c" + (eliminatedTeams.isEmpty()
                    ? "none" : String.join(", ", eliminatedTeams))));
            sender.sendMessage(color("&7Players assigned: &e" + playerTeams.size()));
            return true;
        }

        if (!sender.hasPermission("castlecore.admin")) return noPermission(sender);

        if (sub.equals("start")) {
            if (matchActive) {
                sender.sendMessage(color("&cA match is already running."));
                return true;
            }
            if (cores.size() < 2) {
                sender.sendMessage(color("&cRegister at least two team cores before starting."));
                return true;
            }
            eliminatedTeams.clear();
            cores.clear();
            cores.putAll(registeredCores);
            restoreAllCoreBlocks();
            playerTeams.clear();
            matchActive = true;
            saveData();
            Bukkit.broadcastMessage(color("&6[CastleCore] &aThe castle match has started! "
                    + "&eUse /core join <team> &ato choose your team. You can only join once."));
            return true;
        }

        if (sub.equals("end")) {
            if (!matchActive) {
                sender.sendMessage(color("&cThere is no active match."));
                return true;
            }
            matchActive = false;
            playerTeams.clear();
            eliminatedTeams.clear();
            saveData();
            Bukkit.broadcastMessage(color("&6[CastleCore] &eThe match has ended. "
                    + "Team assignments have been cleared."));
            return true;
        }

        if (sub.equals("reset")) {
            matchActive = false;
            playerTeams.clear();
            eliminatedTeams.clear();
            cores.clear();
            cores.putAll(registeredCores);
            restoreAllCoreBlocks();
            saveData();
            Bukkit.broadcastMessage(color("&6[CastleCore] &aThe match has been reset. "
                    + "All registered beacon cores have been restored and teams unlocked."));
            return true;
        }

        if (sub.equals("remove")) {
            if (args.length != 2) {
                sender.sendMessage(color("&cUsage: /core remove <team>"));
                return true;
            }
            if (matchActive) {
                sender.sendMessage(color("&cEnd the match before removing a core."));
                return true;
            }
            String removeKey = args[1].toLowerCase(Locale.ROOT);
            Core removed = registeredCores.remove(removeKey);
            cores.remove(removeKey);
            if (removed == null) {
                sender.sendMessage(color("&cNo core found for that team."));
            } else {
                saveData();
                sender.sendMessage(color("&aRemoved " + removed.team() + " Team's core registration."));
            }
            return true;
        }

        if (sub.equals("setspawn")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can set a team spawn.");
                return true;
            }
            if (matchActive) {
                player.sendMessage(color("&cEnd the match before changing team spawns."));
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(color("&cUsage: /core setspawn <team>"));
                return true;
            }
            String team = args[1].toLowerCase(Locale.ROOT);
            if (!cores.containsKey(team)) {
                player.sendMessage(color("&cThat team does not have a registered core yet. Set its core first."));
                return true;
            }
            Location location = player.getLocation();
            teamSpawns.put(team, new TeamSpawn(
                    location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch()
            ));
            saveData();
            player.sendMessage(color("&aSaved the &e" + cores.get(team).team()
                    + " &ateam spawn. Players will respawn in the 3x3 area around your position."));
            return true;
        }

        if (sub.equals("spawn")) {
            if (args.length != 2) {
                sender.sendMessage(color("&cUsage: /core spawn <team>"));
                return true;
            }
            String team = args[1].toLowerCase(Locale.ROOT);
            TeamSpawn spawn = teamSpawns.get(team);
            if (spawn == null) {
                sender.sendMessage(color("&cNo spawn has been set for that team."));
            } else {
                sender.sendMessage(color("&e" + args[1] + " Team &7spawn: "
                        + spawn.world() + " " + String.format(Locale.ROOT, "%.1f, %.1f, %.1f",
                        spawn.x(), spawn.y(), spawn.z()) + " &7(3x3 area)"));
            }
            return true;
        }

        if (sub.equals("set")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can set a core.");
                return true;
            }
            if (matchActive) {
                player.sendMessage(color("&cEnd the match before changing core locations."));
                return true;
            }
            if (args.length != 3) {
                player.sendMessage(color("&cUsage: /core set <team> <colour>"));
                return true;
            }

            String team = args[1].toLowerCase(Locale.ROOT);
            DyeColor dye;
            try {
                dye = DyeColor.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                player.sendMessage(color("&cUnknown team colour. Use one of: "
                        + String.join(", ", Arrays.stream(DyeColor.values())
                        .map(c -> c.name().toLowerCase(Locale.ROOT)).toList())));
                return true;
            }

            Block target = player.getTargetBlockExact(8);
            if (target == null || target.getType().isAir()) {
                player.sendMessage(color("&cLook at a solid block to place the beacon on."));
                return true;
            }

            Block beaconBlock = target.getRelative(BlockFace.UP);
            Block glassBlock = beaconBlock.getRelative(BlockFace.UP);
            if (!beaconBlock.isEmpty() || !glassBlock.isEmpty()) {
                player.sendMessage(color("&cThe two spaces above that block must be empty (beacon and glass)."));
                return true;
            }

            beaconBlock.setType(Material.BEACON, false);
            glassBlock.setType(Material.valueOf(dye.name() + "_STAINED_GLASS"), false);
            Core replacement = new Core(args[1], beaconBlock.getWorld().getName(),
                    beaconBlock.getX(), beaconBlock.getY(), beaconBlock.getZ(), dye.name());
            Core previous = registeredCores.put(team, replacement);
            cores.put(team, replacement);
            eliminatedTeams.remove(team);
            saveData();

            if (previous != null) {
                player.sendMessage(color("&eReplaced the previous " + previous.team() + " Team core."));
            }
            player.sendMessage(color("&aPlaced a &e" + dye.name().toLowerCase(Locale.ROOT)
                    + " &astained-glass marker above the &e" + args[1] + " Team &abeacon."));
            return true;
        }

        help(sender);
        return true;
    }

    private void restoreAllCoreBlocks() {
        for (Core core : registeredCores.values()) {
            World world = Bukkit.getWorld(core.world());
            if (world == null) continue;
            Block beacon = world.getBlockAt(core.x(), core.y(), core.z());
            Block glass = beacon.getRelative(BlockFace.UP);
            try {
                beacon.setType(Material.BEACON, false);
                glass.setType(Material.valueOf(core.color() + "_STAINED_GLASS"), false);
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Could not restore beacon core for team " + core.team());
            }
        }
    }

    private void checkForWinner() {
        Set<String> remaining = cores.keySet();
        if (remaining.size() == 1) {
            String winnerKey = remaining.iterator().next();
            Core winner = cores.get(winnerKey);
            matchActive = false;
            saveData();
            Bukkit.broadcastMessage(color("&6[CastleCore] &a" + winner.team()
                    + " Team is the last castle standing and wins the match!"));
            Bukkit.broadcastMessage(color("&7An admin can run &f/core reset &7to prepare the next match."));
        } else if (remaining.isEmpty()) {
            matchActive = false;
            saveData();
            Bukkit.broadcastMessage(color("&6[CastleCore] &eAll castle cores have been destroyed. No winner."));
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(color("&6&lCastleCore &7- Commands"));
        sender.sendMessage(color("&e/core set <team> <colour> &7- Place a beacon core with matching stained glass"));
        sender.sendMessage(color("&e/core setspawn <team> &7- Set the team respawn area (3x3)"));
        sender.sendMessage(color("&e/core spawn <team> &7- Show a team spawn location"));
        sender.sendMessage(color("&e/core start &7- Start a new match"));
        sender.sendMessage(color("&e/core join <team> &7- Join once; cannot switch"));
        sender.sendMessage(color("&e/core end &7- End match and unlock players"));
        sender.sendMessage(color("&e/core reset &7- End match, clear teams and restore all beacon cores"));
        sender.sendMessage(color("&e/core status &7- Show match status"));
        sender.sendMessage(color("&e/core list &7- List active beacon cores"));
        sender.sendMessage(color("&e/core remove <team> &7- Remove a core (before match)"));
    }

    private boolean noPermission(CommandSender sender) {
        sender.sendMessage(color("&cYou do not have permission to do that."));
        return true;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    private record TeamSpawn(String world, double x, double y, double z, float yaw, float pitch) { }

    private record Core(String team, String world, int x, int y, int z, String color) {
        boolean matches(Block block) {
            return block.getWorld().getName().equals(world)
                    && block.getX() == x && block.getY() == y && block.getZ() == z;
        }
        boolean matchesGlass(Block block) {
            return block.getWorld().getName().equals(world)
                    && block.getX() == x && block.getY() == y + 1 && block.getZ() == z;
        }
    }
}
