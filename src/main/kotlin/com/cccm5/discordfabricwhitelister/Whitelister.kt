package com.cccm5.discordfabricwhitelister

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.server.ServerStartCallback
import net.fabricmc.fabric.api.event.server.ServerStopCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.WhitelistEntry
import net.minecraft.server.dedicated.MinecraftDedicatedServer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import org.javacord.*
import org.javacord.api.DiscordApi
import org.javacord.api.DiscordApiBuilder

@Serializable
data class Config(val token: String)

@Suppress("unused")
object Whitelister: DedicatedServerModInitializer {
    private val LOGGER: Logger = LogManager.getLogger()
    private val servers: MutableList<MinecraftDedicatedServer> = mutableListOf()
    override fun onInitializeServer() {
        // load bot token
        val cfgFile = File(FabricLoader.getInstance().configDirectory, "whitelister.json")
        val json = Json(JsonConfiguration.Stable)
        if(!cfgFile.exists()){
            if(!cfgFile.createNewFile()){
                LOGGER.error("Failed to create config file")
                return
            }
            if(!cfgFile.canWrite()){
                LOGGER.error("Can't write to config file")
                return
            }
            cfgFile.writeText(json.stringify(Config.serializer(), Config("YOUR_TOKEN_HERE")))
        }
        if(!cfgFile.canRead()){
            LOGGER.error("Can't read config file")
            return
        }
        lateinit var token: String
        try {
            token = json.parse(Config.serializer(), cfgFile.readText()).token
        } catch (e: Exception){
            LOGGER.error("Invalid config file.")
            return
        }
        if(token.equals("YOUR_TOKEN_HERE",true)){
            LOGGER.error("Change the default discord token in the config file")
            return
        }
        lateinit var api: DiscordApi
        try {
            api = DiscordApiBuilder().setToken(token).login().join()
        } catch (e: Exception){
            LOGGER.error("Could not login to discord")
        }
        // register minecraft server events
        ServerStartCallback.EVENT.register(ServerStartCallback { server: MinecraftServer ->
            if(server is MinecraftDedicatedServer) {
                LOGGER.info("Adding server ${server.name}")
                servers.add(server)
            }
        })
        ServerStopCallback.EVENT.register(ServerStopCallback { server: MinecraftServer ->
            if(server is MinecraftDedicatedServer) {
                LOGGER.info("Removing server $server")
                servers.remove(server)
            }
        })
        api.addMessageCreateListener {
            val message = it.message
            val discordUser = it.messageAuthor
            if (message.content.startsWith("!whitelist")) {
                if (message.content.trim().equals("!whitelist", true)) {
                    message.channel.sendMessage("I need a username to whitelist.")
                } else {
                    val (_, userName) = message.content.split(" ", limit = 2)
                    if (userName.isNotEmpty()) {
                        for (server in servers) {
                            //                        server.playerManager.getPlayer(userName)
                            if (!server.playerManager.isWhitelistEnabled) {
                                message.channel.sendMessage("I can't whitelist you on a server without a whitelist.")
                                continue
                            }
                            val userProfile = server.userCache.findByName(userName)
                            if(userProfile == null){
                                message.channel.sendMessage("Invalid user profile")
                                continue
                            }
                            if (server.playerManager.isWhitelisted(userProfile)) {
                                message.channel.sendMessage("You're already whitelisted")
                                continue
                            }
                            server.playerManager.whitelist.add(WhitelistEntry(userProfile))
                            message.channel.sendMessage("Whitelisted $userName")
                        }
                        if (servers.isEmpty()){
                            message.channel.sendMessage("I can't whitelist you if there's no severs to be whitelisted on.")
                        }
                    }
                }
            }
        }
    }
}