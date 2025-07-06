package winlyps.frameChunkLimit

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.GlowItemFrame
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.*

class FrameChunkLimit : JavaPlugin(), Listener {

    private var globalLimit: Int = -1 // -1 means no limit
    private var watchService: WatchService? = null
    private var watcherThread: Thread? = null

    override fun onEnable() {
        saveDefaultConfig()
        config.options().copyDefaults(true)
        globalLimit = config.getInt("frame-limit", -1)

        getCommand("framechunk")?.setExecutor(this)
        server.pluginManager.registerEvents(this, this)
        logger.info("FrameChunkLimit enabled.")

        startConfigWatcher()
    }

    override fun onDisable() {
        stopConfigWatcher()
        logger.info("FrameChunkLimit disabled.")
    }

    private fun startConfigWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService()
            dataFolder.toPath().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)

            watcherThread = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    val key = try {
                        watchService?.take()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    } catch(e: ClosedWatchServiceException) {
                        return@Thread
                    }

                    key ?: continue

                    for (event in key.pollEvents()) {
                        val fileName = event.context() as? Path ?: continue
                        if (fileName.toString() == "config.yml") {
                            server.scheduler.runTask(this, Runnable {
                                reloadConfig()
                                val newLimit = config.getInt("frame-limit", -1)
                                if (globalLimit != newLimit) {
                                    globalLimit = newLimit
                                    logger.info("Auto-reloaded frame limit from config.yml. New limit: $globalLimit")
                                }
                            })
                        }
                    }

                    if (!key.reset()) {
                        break
                    }
                }
            }
            watcherThread?.start()
        } catch (e: Exception) {
            logger.severe("Could not start config watcher: ${e.message}")
        }
    }

    private fun stopConfigWatcher() {
        watcherThread?.interrupt()
        try {
            watchService?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name.equals("framechunk", ignoreCase = true)) {
            if (sender !is Player) {
                sender.sendMessage("This command can only be used by a player.")
                return true
            }

            if (!sender.hasPermission("framechunklimit.set")) {
                sender.sendMessage("You do not have permission to use this command.")
                return true
            }

            if (args.isEmpty()) {
                sender.sendMessage("Current limit: $globalLimit. Usage: /framechunk <limit>")
                return true
            }

            if (args.size != 1) {
                sender.sendMessage("Usage: /framechunk <limit>")
                return true
            }

            val limit = args[0].toIntOrNull()
            if (limit == null || limit < -1) { // Allow -1 for unlimited
                sender.sendMessage("The limit must be a non-negative integer or -1 for unlimited.")
                return true
            }

            globalLimit = limit
            config.set("frame-limit", limit)
            saveConfig()
            sender.sendMessage("Global frame chunk limit has been set to $limit.")
            return true
        }
        return false
    }

    @EventHandler
    fun onHangingPlace(event: HangingPlaceEvent) {
        if (globalLimit < 0) return

        val player = event.player ?: return
        val entity = event.entity
        if (entity is ItemFrame || entity is GlowItemFrame) {
            val chunk = event.block.chunk

            val frameCount = chunk.entities.count { it is ItemFrame || it is GlowItemFrame }

            if (frameCount >= globalLimit) {
                event.isCancelled = true
                player.sendMessage("You have reached the frame limit ($globalLimit) for this chunk.")
            }
        }
    }
}
