package net.sourcebot

import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.GenericEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.EventListener
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent.DIRECT_MESSAGE_TYPING
import net.dv8tion.jda.api.requests.GatewayIntent.GUILD_MESSAGE_TYPING
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder
import net.sourcebot.api.alert.EmbedAlert
import net.sourcebot.api.command.CommandHandler
import net.sourcebot.api.database.MongoDB
import net.sourcebot.api.database.MongoSerial
import net.sourcebot.api.event.EventSystem
import net.sourcebot.api.event.SourceEvent
import net.sourcebot.api.module.*
import net.sourcebot.api.permission.PermissionHandler
import net.sourcebot.api.permission.SourcePermission
import net.sourcebot.api.permission.SourceRole
import net.sourcebot.api.permission.SourceUser
import net.sourcebot.api.properties.JsonSerial
import net.sourcebot.api.properties.Properties
import net.sourcebot.impl.command.GuildInfoCommand
import net.sourcebot.impl.command.HelpCommand
import net.sourcebot.impl.command.PermissionsCommand
import net.sourcebot.impl.command.TimingsCommand
import net.sourcebot.impl.command.lifecycle.RestartCommand
import net.sourcebot.impl.command.lifecycle.StopCommand
import net.sourcebot.impl.command.lifecycle.UpdateCommand
import uk.org.lidalia.sysoutslf4j.context.SysOutOverSLF4J
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

class Source internal constructor(val properties: Properties) : SourceModule() {
    private val ignoredIntents = EnumSet.of(
        GUILD_MESSAGE_TYPING, DIRECT_MESSAGE_TYPING
    )
    val globalAdmins: Set<String> = properties.required("global-admins")

    val sourceEventSystem = EventSystem<SourceEvent>()
    val jdaEventSystem = EventSystem<GenericEvent>()

    val mongodb = MongoDB(properties.required("mongodb"))
    val permissionHandler = PermissionHandler(mongodb, globalAdmins)
    val moduleHandler = ModuleHandler()

    val commandHandler = CommandHandler(
        properties.required("commands.prefix"),
        properties.required("commands.delete-seconds"),
        globalAdmins,
        permissionHandler
    )

    val shardManager = DefaultShardManagerBuilder.create(
        properties.required("token"),
        EnumSet.complementOf(ignoredIntents)
    ).addEventListeners(
        EventListener(jdaEventSystem::fireEvent),
        object : ListenerAdapter() {
            override fun onMessageReceived(event: MessageReceivedEvent) {
                commandHandler.onMessageReceived(event)
            }
        }
    ).setActivityProvider {
        Activity.watching("TSC. Shard $it")
    }.build()

    init {
        shardManager.shards.forEach { it.awaitReady() }
        instance = this
        EmbedAlert.footer = properties.required("alert.footer")
        classLoader = object : ModuleClassLoader() {
            override fun findClass(name: String, searchParent: Boolean): Class<*> {
                return try {
                    if (searchParent) moduleHandler.findClass(name)
                    else Source::class.java.classLoader.loadClass(name)
                } catch (ex: Exception) {
                    null
                } ?: throw ClassNotFoundException(name)
            }
        }
        descriptor = this.javaClass.getResourceAsStream("/module.json").use {
            if (it == null) throw InvalidModuleException("Could not find module.json!")
            else JsonSerial.mapper.readTree(it)
        }.let(::ModuleDescriptor)

        registerSerial()
        loadModules()

        logger.info("Source is now online!")
    }

    private fun registerSerial() {
        MongoSerial.register(SourcePermission.Serial())
        MongoSerial.register(SourceUser.Serial(permissionHandler))
        MongoSerial.register(SourceRole.Serial(permissionHandler))
    }

    private fun loadModules() {
        val modulesFolder = File("modules")
        if (!modulesFolder.exists()) modulesFolder.mkdir()
        logger.info("Loading modules...")
        moduleHandler.loadModule(this)
        val modules = moduleHandler.loadModules(modulesFolder)
        logger.info("Enabling modules...")
        moduleHandler.enableModule(this)
        modules.forEach(moduleHandler::enableModule)
        logger.info("All modules have been enabled!")
    }

    override fun onEnable(source: Source) {
        registerCommands(
            HelpCommand(moduleHandler, commandHandler),
            GuildInfoCommand(),
            TimingsCommand(),
            PermissionsCommand(permissionHandler),

            RestartCommand(properties.required("lifecycle.restart")),
            StopCommand(properties.required("lifecycle.stop")),
            UpdateCommand(properties.required("lifecycle.update"))
        )
    }

    companion object {
        @JvmField val TIME_ZONE: ZoneId = ZoneId.of("America/New_York")
        @JvmField val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "MM/dd/yyyy hh:mm:ss a z"
        ).withZone(TIME_ZONE)
        @JvmField val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "hh:mm:ss a z"
        ).withZone(TIME_ZONE)
        @JvmField val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "MM/dd/yyyy"
        ).withZone(TIME_ZONE)

        @JvmStatic internal lateinit var instance: Source
        var enabled = false
            internal set

        @JvmStatic fun main(args: Array<String>) {
            SysOutOverSLF4J.sendSystemOutAndErrToSLF4J()
            start()
        }

        @JvmStatic fun start(): Source {
            if (enabled) throw IllegalStateException("Source is already enabled!")
            enabled = true
            JsonSerial.registerSerial(Properties.Serial())

            val configFile = File("config.json")
            if (!configFile.exists()) {
                Source::class.java.getResourceAsStream("/config.example.json").use {
                    Files.copy(it, Path.of("config.json"))
                }
            }
            val properties = JsonSerial.mapper.readValue(configFile, Properties::class.java)
            return Source(properties)
        }
    }
}