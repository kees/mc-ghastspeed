package net.outflux.mc.ghastspeed;

import java.lang.String;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.HappyGhast;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

public class GhastSpeed extends JavaPlugin implements Listener {
    private double originalSpeed;
    private double globalSpeed;
    private final Map<UUID, Double> ghastSpeeds = new HashMap<>();

    private void discoverDefaults() {
        // Figure out what the default flying speed of a Happy Ghast is
        World nether = Bukkit.getWorld("world_nether"); // Pick a safe world
        if (nether == null) return;

        Location unoccupied = new Location(nether, 0, 200, 0); // Best effort safe location
        if (unoccupied == null) return;

        LivingEntity entity = (LivingEntity) nether.spawnEntity(unoccupied, EntityType.HAPPY_GHAST);
        if (entity == null) return;

        originalSpeed = entity.getAttribute(Attribute.FLYING_SPEED).getBaseValue();
        entity.remove();
        getLogger().info("Happy Ghast default flying speed: " + originalSpeed);
    }

    private void readConfig() {
        // Saves default config.yml if not present
        saveDefaultConfig();

        // Access config values
        globalSpeed = getConfig().getDouble("global-speed");
        getLogger().info("Happy Ghast global ridden flying speed: " + globalSpeed);

        // Deserialize from strings to UUID/doubles
        ghastSpeeds.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("ghast-speeds");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    double speed = section.getDouble(key);
                    ghastSpeeds.put(uuid, speed);
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("Invalid UUID in config, ignoring: " + key);
                }
            }
        }
    }

    private void writeConfig() {
        Map<String, Object> saveMap = new HashMap<>();
        for (Map.Entry<UUID, Double> entry : ghastSpeeds.entrySet()) {
            saveMap.put(entry.getKey().toString(), entry.getValue());
        }
        getConfig().set("ghast-speeds", saveMap);
        getConfig().set("global-speed", globalSpeed);
        saveConfig();
    }

    private String blocksPerSecondString(double blocksPerTick)
    {
        double blocksPerSec = blocksPerTick * 20.0;
        return String.format("%f block%s/tick (%f block%s/second)",
                             blocksPerTick, blocksPerTick == 1.0 ? "" : "s",
                             blocksPerSec, blocksPerSec == 1.0 ? "" : "s");
    }

    @Override
    public void onEnable() {
        originalSpeed = 0.0;
        discoverDefaults();
        if (originalSpeed == 0.0) {
            originalSpeed = 0.05;
            getLogger().info("Could not discover default Happy Ghast flying speed, using hard-coded default: " + blocksPerSecondString(originalSpeed));
        }

        // load config.yml
        readConfig();

        // Hook up the event handlers
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void setMountSpeed(Entity rider, LivingEntity mount)
    {
        // Ignore non-Ghasts
        if (!(mount instanceof HappyGhast)) return;

        // Ignore impossible lack of FLYING_SPEED attribute
        AttributeInstance speedAttr = mount.getAttribute(Attribute.FLYING_SPEED);
        if (speedAttr == null) return;

        double currentSpeed = speedAttr.getBaseValue();
        double speed = ghastSpeeds.getOrDefault(mount.getUniqueId(), globalSpeed);

        // Ignore unchanged speeds.
        if (speed == currentSpeed) return;

        speedAttr.setBaseValue(speed);
        getLogger().info(rider.getName() + " mounted " + mount.getName() + "; flying speed changed from " + blocksPerSecondString(currentSpeed) + " to " + blocksPerSecondString(speed));
    }

    @EventHandler
    public void onMount(EntityMountEvent event) {
        if (!(event.getMount() instanceof HappyGhast)) return;

        LivingEntity mount = (LivingEntity) event.getMount();
        Entity rider = event.getEntity();

        setMountSpeed(rider, mount);
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getDismounted() instanceof HappyGhast)) return;

        LivingEntity mount = (LivingEntity) event.getDismounted();
        Entity rider = event.getEntity();

        AttributeInstance speedAttr = mount.getAttribute(Attribute.FLYING_SPEED);
        // Ignore impossible lack of FLYING_SPEED attribute
        if (speedAttr == null) return;

        // Reset to default speed
        speedAttr.setBaseValue(originalSpeed);
        getLogger().info(rider.getName() + " dismounted " + mount.getName() + "; flying speed restored to " + blocksPerSecondString(originalSpeed));
    }

    // Negative speeds are nonsense, and speeds above 1.0 are almost certain to break clients.
    private double parseSaneSpeed(String valueString) {
        double speed = Double.parseDouble(valueString);
        if (speed < 0.0 || speed > 1.0) {
            throw new NumberFormatException("out of sane range (0.0 - 1.0)");
        }
        return speed;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(command.getName().equalsIgnoreCase("ghastspeed"))) return false;

        // Report current speeds
        if (args.length == 0) {
            sender.sendMessage("Global Happy Ghast flying speed: " + blocksPerSecondString(originalSpeed));
            sender.sendMessage("Global Happy Ghast ridden flying speed: " + blocksPerSecondString(globalSpeed));

            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;

            Entity vehicle = player.getVehicle();
            if (!(vehicle instanceof HappyGhast)) return true;
            LivingEntity mount = (LivingEntity) vehicle;

            AttributeInstance speedAttr = mount.getAttribute(Attribute.FLYING_SPEED);
            // Ignore impossible lack of FLYING_SPEED attribute
            if (speedAttr == null) return true;

            double currentSpeed = speedAttr.getBaseValue();
            player.sendMessage("Current Happy Ghast ridden flying speed: " + blocksPerSecondString(currentSpeed));

            return true;
        }

        boolean success = false;
        try {
            if (args.length == 2 && args[0].equalsIgnoreCase("global")) {
                // /ghastspeed global NN.NN
                // Set the default flying speed of a newly mounted Happy Ghast
                if (!sender.hasPermission("ghastspeed.set.global")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to set the global speed.");
                    return true;
                }

                double speed = parseSaneSpeed(args[1]);
                globalSpeed = speed;
                sender.sendMessage("You set the global Ghast ridden flying speed to: " + globalSpeed);
                success = true;
            }
            else if (args.length == 1 && !(args[0].equalsIgnoreCase("help"))) {
                // /ghastspeed NN.NN
                // Set the flying speed of the currently mounted Happy Ghast
                if (!sender.hasPermission("ghastspeed.set")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to set this Ghast's speed.");
                    return true;
                }

                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "This command can only be run by a player.");
                    return true;
                }
                Player player = (Player) sender;

                Entity vehicle = player.getVehicle();
                if (!(vehicle instanceof HappyGhast)) {
                    player.sendMessage("You must be riding a Happy Ghast to set its flying speed");
                    return true;
                }
                LivingEntity mount = (LivingEntity) vehicle;

                double speed = parseSaneSpeed(args[0]);
                ghastSpeeds.put(mount.getUniqueId(), speed);
                setMountSpeed(player, mount);

                player.sendMessage("You set this Ghast's ridden flying speed to: " + blocksPerSecondString(speed));
                success = true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid speed: " + e.getMessage());
        }

        if (success) {
            // Either the global or specific ghast speed changed: save results.
            writeConfig();
        } else {
            // Something went wrong, so show usage.
            sender.sendMessage("Usage: /ghastspeed [[global] <value in blocks-per-tick>]");
        }

        return true;
    }
}

// vim: set expandtab shiftwidth=4 softtabstop=4 :
