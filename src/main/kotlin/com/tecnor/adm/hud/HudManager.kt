package com.tecnor.adm.hud

import com.tecnor.adm.ADMPlugin
import com.tecnor.adm.api.*
import com.tecnor.adm.command.AdminService
import com.tecnor.adm.message.MessageService
import com.tecnor.adm.module.*
import com.tecnor.adm.service.*
import com.tecnor.adm.settings.SettingsSnapshot
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.*
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.ServicePriority
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class HudManager(
    val plugin: ADMPlugin, val modules: ModuleManager, val messages: MessageService,
    storage: Storage, private val admin: AdminService, val providers: EnderChestProviderRegistry
) : FeatureService, Listener {
    override val id = "hud"
    val storage: Storage = ScopedStorage(storage, id)
    override val commands = listOf(CommandSpec("adm-hud", listOf("admhud")))
    val registry = HudRegistry()
    val sessions = ConcurrentHashMap<UUID, HudSession>()
    private val builder = HudItemBuilder(this)
    @Volatile private var stopping = false
    val statusChecker = StatusChecker(this)
    private val queuedClicks = mutableSetOf<UUID>()
    var extraScreen: ((String) -> HudScreen?)? = null
    var indicator: ((HudAction, Player) -> List<String>)? = null
    var screenChanged: (() -> Unit)? = null

    fun value(path: String): Any? = plugin.settings().current().value("hud.$path")
    fun text(path: String, fallback: String) = value(path) as? String ?: fallback
    fun number(path: String, fallback: Int) = (value(path) as? Number)?.toInt() ?: fallback
    fun flag(path: String, fallback: Boolean = true) = value(path) as? Boolean ?: fallback
    fun lines(path: String, fallback: List<String>) = (value(path) as? List<*>)?.filterIsInstance<String>() ?: fallback
    fun material(path: String, fallback: Material) = Material.matchMaterial(text(path, fallback.name))?.takeUnless { it.isAir } ?: fallback
    fun slot(key: String, fallback: Int) = number("slots.$key", fallback).coerceIn(45, 53)
    fun contentSlots(session: HudSession): List<Int> {
        val configured = (value("layout.content-slots") as? List<*>)?.mapNotNull { (it as? Number)?.toInt()?.takeIf { slot -> slot in 0..44 } }?.distinct()
        val slots = configured?.takeIf { it.size >= 9 } ?: (0..44).toList()
        return if (session.preferences.compact) slots.take(27) else slots
    }
    fun service(module: String): FeatureService? = modules.features().firstOrNull { it.id() == module }?.service
    fun moduleAvailable(module: String) = modules.isEnabled(module) && plugin.settings().current().modules().getOrDefault(module, true)
    fun categoryAvailable(category: HudCategory) = flag("categories.${category.id}.enabled") && category.modules.any(::moduleAvailable)
    fun built(category: String) = category in HudActions.actions || category == "settings" || extraScreen?.invoke(category) != null
    fun screen(category: String): HudScreen = registry.create(category) ?: extraScreen?.invoke(category)
        ?: if (category == "settings") SettingsScreen() else CategoryScreen(category)

    override fun enable() {
        stopping = false
        extraScreen = { category -> when (category) {
            "reports" -> ReportsScreen()
            "logs" -> LogsScreen()
            "integrations" -> IntegrationsScreen()
            "status" -> StatusScreen()
            else -> null
        } }
        indicator = { action, viewer -> statusChecker.action(action, viewer).lore() }
        screenChanged = {
            statusChecker.updateLiveTask()
            if (sessions.isEmpty()) statusChecker.clearCache()
        }
        plugin.server.servicesManager.register(HudRegistry::class.java, registry, plugin, ServicePriority.Normal)
        plugin.server.servicesManager.register(HudManager::class.java, this, plugin, ServicePriority.Normal)
        plugin.server.servicesManager.register(StatusChecker::class.java, statusChecker, plugin, ServicePriority.Normal)
    }
    override fun execute(actor: CommandActor, command: String, arguments: String): ActionResult {
        if (!actor.hasPermission("adm.hud.use")) return ActionResult.failure("command.no-permission")
        val player = actor.playerId()?.let(Bukkit::getPlayer) ?: return ActionResult.failure("command.player-only")
        if (arguments.isNotBlank()) return ActionResult.failure("command.usage", mapOf("usage" to "/adm-hud"))
        open(player)
        return ActionResult.success("hud.opened")
    }
    override fun suggestions(command: String, completed: List<String>) = emptyList<String>()

    fun open(viewer: Player, screen: HudScreen = HomeScreen()) {
        if (stopping || !viewer.hasPermission("adm.hud.use")) return
        close(viewer.uniqueId)
        val session = HudSession(viewer.uniqueId, screen, HudTarget(viewer.uniqueId, viewer.name))
        sessions[viewer.uniqueId] = session
        show(session, viewer)
        val revision = session.preferenceRevision
        val id = viewer.uniqueId.toString()
        load(session, { db ->
            db.prepareStatement("SELECT sounds,confirmations,compact FROM hud_preferences WHERE uuid=?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rows -> if (rows.next()) HudPreferences(rows.getBoolean(1), rows.getBoolean(2), rows.getBoolean(3)) else HudPreferences() }
            }
        }) { preferences, error ->
            if (error == null && preferences != null && session.preferenceRevision == revision) { session.preferences = preferences; refresh(session, viewer) }
        }
        audit(viewer, session.target, "OPEN", "")
    }
    fun navigate(session: HudSession, viewer: Player, screen: HudScreen, push: Boolean = true) {
        if (!active(session)) return
        if (push) session.stack.addLast(session.screen)
        cancelInput(session)
        session.screen = screen
        show(session, viewer)
    }
    private fun show(session: HudSession, viewer: Player) {
        session.revision++
        val holder = HudHolder(viewer.uniqueId, session.token)
        val inventory = Bukkit.createInventory(holder, 54, builder.text(text("titles.${session.screen.id}", session.screen.title)))
        holder.contents = inventory
        session.switching = true
        session.inventory = inventory
        try { refresh(session, viewer); viewer.openInventory(inventory) } finally { session.switching = false }
        if (viewer.openInventory.topInventory !== inventory) { close(viewer.uniqueId); return }
        session.screen.opened(this, session, viewer)
        screenChanged?.invoke()
    }
    fun refresh(session: HudSession, viewer: Player) {
        if (!active(session)) return
        if (!screenAllowed(session, viewer)) { close(session.viewer); return }
        val inventory = session.inventory ?: return
        val buttons = session.screen.buttons(this, session, viewer) + registry.buttons(session.screen.id) + listOf(
            HudButton(slot("back", 45), "back", "Back", Material.ARROW) { m, s, p, _ -> m.navigate(s, p, s.stack.removeLastOrNull() ?: HomeScreen(), false) },
            HudButton(slot("home", 46), "home", "Home", Material.COMPASS) { m, s, p, _ -> s.stack.clear(); m.navigate(s, p, HomeScreen(), false) },
            HudButton(slot("close", 53), "close", "Close", Material.BARRIER) { m, s, _, _ -> m.close(s.viewer) }
        )
        session.buttons = buttons.filter { it.slot in 0..53 }.associateBy { it.slot }
        val filler = builder.build(HudButton(0, "filler", " ", material("filler.material", Material.BLACK_STAINED_GLASS_PANE)) { _, _, _, _ -> }, viewer)
        val border = builder.build(HudButton(0, "border", " ", material("border.material", Material.GRAY_STAINED_GLASS_PANE)) { _, _, _, _ -> }, viewer)
        for (slot in 0..53) {
            val desired = session.buttons[slot]?.let { builder.build(it, viewer) }
                ?: if (slot >= 45 && flag("border.enabled")) border else if (flag("filler.enabled")) filler else null
            if (inventory.getItem(slot) != desired) inventory.setItem(slot, desired?.clone())
        }
    }
    fun active(session: HudSession) = !stopping && sessions[session.viewer] === session
    private fun screenAllowed(session: HudSession, viewer: Player): Boolean {
        if (!viewer.hasPermission("adm.hud.use")) return false
        val category = (session.stack.toList() + session.screen).lastOrNull { screen -> HudActions.categories.any { it.id == screen.id } }?.id
        val definition = HudActions.categories.firstOrNull { it.id == category } ?: return true
        return viewer.hasPermission("adm.hud.category.${definition.id}") && categoryAvailable(definition)
    }
    private fun post(session: HudSession, action: () -> Unit) {
        synchronized(session) {
            if (!active(session)) return
            lateinit var task: org.bukkit.scheduler.BukkitTask
            task = plugin.server.scheduler.runTask(plugin, Runnable {
                synchronized(session) { session.tasks.remove(task) }
                if (active(session)) action()
            })
            session.tasks.add(task)
        }
    }
    fun <T> load(session: HudSession, work: (Connection) -> T, apply: (T?, Throwable?) -> Unit) {
        watch(session, storage.submit(work), apply)
    }
    fun <T> watch(session: HudSession, future: CompletableFuture<T>, apply: (T?, Throwable?) -> Unit) {
        synchronized(session) { session.futures.add(future) }
        future.whenComplete { result, error ->
            synchronized(session) {
                session.futures.remove(future)
                if (!active(session)) return@whenComplete
                post(session) { apply(result, error) }
            }
        }
    }
    fun savePreferences(session: HudSession, viewer: Player) {
        session.preferenceRevision++
        val id = session.viewer.toString()
        val preferences = session.preferences.copy()
        val saved = storage.submit { db ->
            db.prepareStatement("INSERT INTO hud_preferences(uuid,sounds,confirmations,compact) VALUES(?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET sounds=excluded.sounds,confirmations=excluded.confirmations,compact=excluded.compact").use { statement ->
                statement.setString(1, id); statement.setBoolean(2, preferences.sounds)
                statement.setBoolean(3, preferences.confirmations); statement.setBoolean(4, preferences.compact)
                statement.executeUpdate()
            }
        }
        watch(session, saved.thenApply { it }) { _, error -> if (error != null) messages.send(CommandActor.from(viewer), ActionResult.failure("storage.unavailable")) }
        audit(viewer, session.target, "SETTINGS", preferences.toString())
    }
    fun ask(session: HudSession, viewer: Player, answer: (String) -> Unit) {
        cancelInput(session)
        session.awaitingChat = true
        session.input = answer
        session.switching = true
        try { viewer.closeInventory() } finally { session.switching = false }
        session.timeout = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (active(session) && session.awaitingChat) { messages.send(CommandActor.from(viewer), ActionResult.failure("hud.input-timeout")); close(session.viewer) }
        }, number("input-timeout-seconds", 60).coerceIn(5, 600) * 20L)
        messages.send(CommandActor.from(viewer), ActionResult.success("hud.input-prompt"))
        screenChanged?.invoke()
    }
    private fun cancelInput(session: HudSession) {
        session.timeout?.cancel(); session.timeout = null
        session.input = null; session.awaitingChat = false
    }
    fun close(id: UUID) {
        val session = sessions.remove(id) ?: return
        synchronized(session) {
            cancelInput(session)
            session.tasks.forEach { it.cancel() }; session.tasks.clear()
            session.futures.toList().forEach { it.cancel(false) }; session.futures.clear()
        }
        queuedClicks.remove(id)
        session.stack.clear(); session.buttons = emptyMap()
        Bukkit.getPlayer(id)?.let { if (it.openInventory.topInventory.holder is HudHolder) it.closeInventory() }
        session.inventory = null
        if (sessions.isEmpty()) builder.clear()
        screenChanged?.invoke()
    }
    override fun onReload(snapshot: SettingsSnapshot) { sessions.keys.toList().forEach(::close); builder.clear() }
    override fun disable() {
        stopping = true
        statusChecker.close()
        sessions.keys.toList().forEach(::close)
        registry.clear(); builder.clear()
        plugin.server.servicesManager.unregister(this); plugin.server.servicesManager.unregister(registry)
        plugin.server.servicesManager.unregister(statusChecker)
    }
    fun startAction(session: HudSession, viewer: Player, action: HudAction) {
        if (!viewer.hasPermission(action.node) || !moduleAvailable(action.module)) return
        if (action.command in listOf("warnings", "history", "alts")) {
            navigate(session, viewer, PunishmentPageScreen(action, session.target))
            audit(viewer, session.target, action.command.uppercase(), "viewer")
            return
        }
        if (action.input) navigate(session, viewer, InputScreen(action))
        else submitAction(session, viewer, action, action.argument.takeUnless { it == "random" }.orEmpty())
    }
    fun submitAction(session: HudSession, viewer: Player, action: HudAction, input: String) {
        val arguments = if (action.argument == "random") {
            val target = Bukkit.getOnlinePlayers().filter { it.uniqueId != viewer.uniqueId && viewer.canSee(it) }.randomOrNull()
                ?: run { messages.send(CommandActor.from(viewer), ActionResult.failure("command.player-not-found", mapOf("target" to "random player"))); return }
            session.target = HudTarget(target.uniqueId, target.name)
            target.name
        } else if (action.target) {
            val name = if (action.module in listOf("punishments", "inventory") || action.command in listOf("whois", "seen")) session.target.id.toString()
                else Bukkit.getPlayer(session.target.id)?.name ?: session.target.id.toString()
            when (action.command) { "gamemode", "speed" -> "$input $name"; else -> "$name${if (input.isBlank()) "" else " $input"}" }
        } else input
        if (action.destructive || session.preferences.confirmations && action.command == "clearchat") navigate(session, viewer, ConfirmScreen(action, arguments, session.target))
        else runAction(session, viewer, action, arguments)
    }
    fun runAction(session: HudSession, viewer: Player, action: HudAction, arguments: String) {
        val actor = CommandActor.from(viewer)
        val target = if (action.target || action.argument == "random") session.target else HudTarget(viewer.uniqueId, viewer.name)
        if (action.command in listOf("invsee", "endersee", "enderedit", "staffmode") || action.destructive) close(session.viewer)
        val result = ActionOrigin.within("HUD") { try {
            if (!viewer.hasPermission(action.node)) ActionResult.failure("command.no-permission")
            else if (!moduleAvailable(action.module)) ActionResult.failure("command.module-unavailable", mapOf("module" to action.module))
            else when (action.command) {
                "reload" -> admin.reload(actor)
                "version" -> admin.version(actor)
                "debug" -> admin.debug(actor)
                "storage-info" -> admin.storageInfo(actor)
                "cleanup" -> admin.cleanup(actor)
                else -> service(action.module)?.execute(actor, action.command, arguments) ?: ActionResult.failure("command.module-unavailable", mapOf("module" to action.module))
            }
        } catch (failure: Throwable) { modules.fail(action.module, failure); ActionResult.failure("command.module-unavailable", mapOf("module" to action.module)) } }
        messages.send(actor, result)
        audit(viewer, target, action.command.uppercase(), arguments, mapOf("accepted" to result.success().toString(), "result" to result.messageKey()))
        if (active(session)) refresh(session, viewer)
    }
    fun audit(viewer: Player, target: HudTarget, action: String, arguments: String, details: Map<String, String> = emptyMap()) {
        plugin.server.servicesManager.load(AuditSink::class.java)?.record(AuditEvent(Instant.now(), viewer.uniqueId, viewer.name, target.id, target.name, "HUD", action,
            details + mapOf("source" to "HUD", "arguments" to arguments)))
    }
    fun actionButton(slot: Int, action: HudAction, session: HudSession, viewer: Player): HudButton {
        val state = state(action, session, viewer)
        val name = action.command + if (action.argument.isNotEmpty()) " (${action.argument})" else ""
        val required = if (!viewer.hasPermission(action.node)) action.node else
            if (action.target && session.target.id != viewer.uniqueId && action.command in listOf("gamemode", "fly", "speed", "god", "heal", "feed", "clear")) "${action.node}.others" else action.node
        val lore = mutableListOf(text("descriptions.${action.command}", "<gray>Use the ${action.command} service action</gray>"),
            "<gray>Syntax: ${escape(action.syntax)}</gray>", "<gray>Aliases: ${escape(action.aliases)}</gray>",
            "<gray>Target: ${escape(if (action.target) session.target.name else viewer.name)}</gray>",
            "<gray>Base node: ${action.node} (${if (viewer.hasPermission(action.node)) "granted" else "missing"})</gray>",
            "<gray>Left: activate; right: select target</gray>", "<gray>Shift: options; middle: refresh</gray>")
        if (state != null) lore += "<aqua>Current state: ${escape(state)}</aqua>"
        if (action.destructive) lore += text("tooltips.destructive", "<red>Destructive: confirmation required</red>")
        if (action.module == "inventory") {
            val provider = providers.select(session.target.id)
            lore += "<gray>Provider: ${provider?.id ?: "none"}; offline: ${provider?.supportsOffline ?: false}</gray>"
            lore += "<gray>Mode: ${if (action.command == "enderedit" || action.command == "invsee" && viewer.hasPermission("adm.admin.invsee.edit")) "editable" else "read-only"}</gray>"
            lore += "<gray>Lock: ${(service("inventory") as? InventoryToolsService)?.isLocked(session.target.id) ?: false}</gray>"
        }
        lore += indicator?.invoke(action, viewer).orEmpty()
        val health = statusChecker.action(action, viewer)
        return HudButton(slot, "${action.command}-${action.argument}", "<${health.color}>$name${if (state == null) "" else ": $state"}</${health.color}>", health.material,
            required, lore, moduleAvailable(action.module)) { m, s, p, click ->
            when {
                click == ClickType.MIDDLE -> { m.refresh(s, p); s.screen.opened(m, s, p) }
                click.isRightClick && action.target -> m.navigate(s, p, PlayerSelectorScreen())
                else -> m.startAction(s, p, action)
            }
        }
    }
    fun state(action: HudAction, session: HudSession, viewer: Player): String? {
        val player = Bukkit.getPlayer(session.target.id)
        fun state(value: Boolean) = if (value) "ON" else "OFF"
        return when (action.command) {
            "fly" -> state(player?.allowFlight == true)
            "god" -> state((service("player-tools") as? PlayerToolsService)?.isGod(session.target.id) == true)
            "vanish" -> state((service("vanish") as? VanishService)?.staffLevel(viewer) != null)
            "staffmode" -> state((service("staff-tools") as? StaffToolsService)?.isStaffMode(viewer.uniqueId) == true)
            "freeze" -> state((service("staff-tools") as? StaffToolsService)?.isFrozen(session.target.id) == true)
            "mute", "tempmute", "unmute" -> state((service("punishments") as? PunishmentService)?.isMuted(session.target.id) == true)
            "mutechat" -> state((service("chat") as? ChatService)?.isMuted() == true)
            "slowmode" -> (service("chat") as? ChatService)?.slowmodeSeconds()?.toString()
            "staffchat" -> state((service("staff-chat") as? StaffCommunicationsService)?.isStaffChat(viewer.uniqueId) == true)
            "spy" -> state((service("staff-chat") as? StaffCommunicationsService)?.isSpy(viewer.uniqueId, action.argument) == true)
            "gamemode" -> player?.gameMode?.name
            "debug" -> state(admin.isDebugging)
            else -> null
        }
    }
    fun escape(text: String) = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(text)

    @EventHandler(priority = EventPriority.LOWEST)
    fun chat(event: AsyncChatEvent) {
        val session = sessions[event.player.uniqueId]?.takeIf { it.awaitingChat } ?: return
        event.isCancelled = true
        val text = PlainTextComponentSerializer.plainText().serialize(event.message()).take(2000)
        post(session) {
                if (!session.awaitingChat) return@post
                val viewer = Bukkit.getPlayer(session.viewer) ?: return@post
                val answer = session.input
                cancelInput(session)
                if (text.equals("cancel", true)) close(session.viewer)
                else { answer?.invoke(text); if (active(session)) show(session, viewer) }
            }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun click(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? HudHolder ?: return
        event.isCancelled = true
        val viewer = event.whoClicked as? Player ?: return
        val session = sessions[viewer.uniqueId] ?: return
        if (!screenAllowed(session, viewer)) { close(session.viewer); return }
        if (holder.token != session.token || session.inventory !== event.view.topInventory || !viewer.hasPermission("adm.hud.use")) return
        if (event.rawSlot !in 0..53 || event.click !in setOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.MIDDLE)) return
        val button = session.buttons[event.rawSlot] ?: return
        if (!button.enabled || button.permission.isNotEmpty() && !viewer.hasPermission(button.permission) || !queuedClicks.add(session.viewer)) return
        val click = event.click
        val revision = session.revision
        post(session) {
            queuedClicks.remove(session.viewer)
            if (active(session) && session.revision == revision) {
                sound(session, viewer)
                audit(viewer, session.target, "BUTTON", button.key)
                try { button.click(this, session, viewer, click) }
                catch (failure: Throwable) { plugin.logger.warning("HUD button failed: ${failure.message}"); close(session.viewer) }
            }
        }
    }
    private fun sound(session: HudSession, viewer: Player) {
        if (flag("sounds.enabled") && session.preferences.sounds) runCatching { Sound.valueOf(text("sounds.click", "UI_BUTTON_CLICK")) }
            .getOrNull()?.let { viewer.playSound(viewer.location, it, 0.5f, 1f) }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun drag(event: InventoryDragEvent) { if (event.view.topInventory.holder is HudHolder) event.isCancelled = true }
    @EventHandler fun closed(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? HudHolder ?: return
        val session = sessions[event.player.uniqueId] ?: return
        if (holder.token == session.token && !session.switching && !session.awaitingChat) close(session.viewer)
    }
    @EventHandler fun quit(event: PlayerQuitEvent) = close(event.player.uniqueId)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun kick(event: PlayerKickEvent) = close(event.player.uniqueId)
    @EventHandler fun pluginDisabled(event: PluginDisableEvent) {
        registry.unregisterAll(event.plugin)
        if (event.plugin !== plugin) sessions.keys.toList().forEach(::close)
    }
}