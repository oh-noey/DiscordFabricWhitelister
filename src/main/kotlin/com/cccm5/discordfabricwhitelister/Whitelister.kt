package com.cccm5.discordfabricwhitelister

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.WhitelistEntry
import net.minecraft.server.dedicated.MinecraftDedicatedServer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent;

@Serializable
data class Config(val token: String)

@Suppress("unused")
object Whitelister: DedicatedServerModInitializer, ListenerAdapter() {
    private val LOGGER: Logger = LogManager.getLogger()
    private val servers: MutableList<MinecraftDedicatedServer> = mutableListOf()
    override fun onInitializeServer() {
        // load bot token
        val cfgFile = File(FabricLoader.getInstance().configDir.toFile(), "whitelister.json")
        if(!cfgFile.exists()){
            if(!cfgFile.createNewFile()){
                LOGGER.error("Failed to create config file")
                return
            }
            if(!cfgFile.canWrite()){
                LOGGER.error("Can't write to config file")
                return
            }
            cfgFile.writeText(Json.encodeToString(Config.serializer(), Config("YOUR_TOKEN_HERE")))
        }
        if(!cfgFile.canRead()){
            LOGGER.error("Can't read config file")
            return
        }
        lateinit var token: String
        try {
            token = Json.decodeFromString<Config>(Config.serializer(), cfgFile.readText()).token
        } catch (e: Exception){
            LOGGER.error("Invalid config file.")
            return
        }
        if(token.equals("YOUR_TOKEN_HERE",true)){
            LOGGER.error("Change the default discord token in the config file")
            return
        }
        lateinit var api: JDA

        // register minecraft server events
        ServerLifecycleEvents.SERVER_STARTED.register { server: MinecraftServer ->
            if(servers.isEmpty()){
                try {
                    api = JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES).addEventListeners(this).setActivity(Activity.playing("Minecraft")).build();
                } catch (e: Exception){
                    LOGGER.error("Could not login to discord")
                }
            }
            if(server is MinecraftDedicatedServer) {
                LOGGER.info("Adding server ${server.name}")
                servers.add(server)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {  server: MinecraftServer ->
            if(server is MinecraftDedicatedServer) {
                LOGGER.info("Removing server $server")
                servers.remove(server)
            }
            if(servers.isEmpty()) api.shutdown()
        }
        
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        val contents = event.message.contentRaw
        val channel = event.channel;
        if(!contents.startsWith("!whitelist")) return
        if (contents.trim().equals("!whitelist", true)) {
            channel.sendMessage("I need a username to whitelist.").queue()
            return
        }
        val (_, userName) = contents.split(" ", limit = 2)
        if (userName.isEmpty()) return
        if (servers.isEmpty()) {
            channel.sendMessage("I can't whitelist you if there's no severs to be whitelisted on.").queue()
            return
        }
        for (server in servers) {
            if (!server.playerManager.isWhitelistEnabled) {
                channel.sendMessage("I can't whitelist you on a server without a whitelist.").queue()
                continue
            }
            val userProfile = server.userCache.findByName(userName)
            if (userProfile == null) {
                channel.sendMessage("Invalid user profile").queue()
                continue
            }
            if (server.playerManager.isWhitelisted(userProfile)) {
                channel.sendMessage("You're already whitelisted").queue()
                continue
            }
            server.playerManager.whitelist.add(WhitelistEntry(userProfile))
            channel.sendMessage("Whitelisted $userName").queue()
        }

    }
}