package com.cccm5.discordfabricwhitelister

import discord4j.core.DiscordClientBuilder
import discord4j.core.event.domain.lifecycle.ReadyEvent
import discord4j.core.event.domain.message.MessageCreateEvent
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
        val client =  DiscordClientBuilder(token).build()
        try {
            client.login().subscribe()
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
        client.eventDispatcher.on(ReadyEvent::class.java).subscribe(
            { ready: ReadyEvent ->
                LOGGER.info("Logged into discord as " + ready.self.username)
            },
            {error -> LOGGER.error(error)}
        )
        client.eventDispatcher.on(MessageCreateEvent::class.java).subscribe({
            val message = it.message
            val discordUser = it.member.orElse(null)
            if (message.content.isPresent && message.content.get().startsWith("!whitelist")) {
                if (message.content.get().trim().equals("!whitelist", true)) {
                    message.channel.block()?.createMessage("I need a username to whitelist.")?.block()
                } else {
                    val (_, userName) = message.content.get().split(" ", limit = 2)
                    if (userName.isNotEmpty()) {
                        for (server in servers) {
    //                        server.playerManager.getPlayer(userName)
                            if (!server.playerManager.isWhitelistEnabled) {
                                message.channel.block()?.createMessage("I can't whitelist you on a server without a whitelist.")?.block()
                                continue
                            }
                            val userProfile = server.userCache.findByName(userName)
                            if(userProfile == null){
                                message.channel.block()?.createMessage("Invalid user profile")?.block()
                                continue
                            }
                            if (server.playerManager.isWhitelisted(userProfile)) {
                                message.channel.block()?.createMessage("You're already whitelisted")?.block()
                                continue
                            }
                            server.playerManager.whitelist.add(WhitelistEntry(userProfile))
                            message.channel.block()?.createMessage("Whitelisted $userName")?.block()
                        }
                        if (servers.isEmpty()){
                            message.channel.block()?.createMessage("I can't whitelist you if there's no severs to be whitelisted on.")?.block()
                        }
                    }
                }
            }
        }, {error -> LOGGER.error(error)})
    }
}