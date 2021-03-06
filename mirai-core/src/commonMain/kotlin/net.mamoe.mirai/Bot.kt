@file:Suppress("EXPERIMENTAL_API_USAGE", "unused")

package net.mamoe.mirai

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.mamoe.mirai.Bot.ContactSystem
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.contact.internal.Group
import net.mamoe.mirai.contact.internal.QQImpl
import net.mamoe.mirai.network.BotNetworkHandler
import net.mamoe.mirai.network.protocol.tim.TIMBotNetworkHandler
import net.mamoe.mirai.network.protocol.tim.packet.login.LoginResult
import net.mamoe.mirai.network.protocol.tim.packet.login.isSuccess
import net.mamoe.mirai.utils.BotConfiguration
import net.mamoe.mirai.utils.DefaultLogger
import net.mamoe.mirai.utils.MiraiLogger
import net.mamoe.mirai.utils.internal.coerceAtLeastOrFail
import net.mamoe.mirai.utils.io.logStacktrace
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmSynthetic

@Suppress("NOTHING_TO_INLINE", "FunctionName")
inline fun BotAccount(id: Long, password: String): BotAccount = BotAccount(id.toUInt(), password)

data class BotAccount(
    val id: UInt,
    val password: String
)

@Suppress("FunctionName")
suspend inline fun Bot(account: BotAccount, logger: MiraiLogger): Bot = Bot(account, logger, coroutineContext)

@Suppress("FunctionName")
suspend inline fun Bot(account: BotAccount): Bot = Bot(account, coroutineContext)

@JvmSynthetic
@Suppress("FunctionName")
suspend inline fun Bot(qq: UInt, password: String): Bot = Bot(qq, password, coroutineContext)

@Suppress("FunctionName")
suspend inline fun Bot(qq: Long, password: String): Bot = Bot(qq.toUInt(), password, coroutineContext)

/**
 * Mirai 的机器人. 一个机器人实例登录一个 QQ 账号.
 * Mirai 为多账号设计, 可同时维护多个机器人.
 *
 * [Bot] 由 3 个模块组成.
 * [联系人管理][ContactSystem]: 可通过 [Bot.contacts] 访问
 * [网络处理器][TIMBotNetworkHandler]: 可通过 [Bot.network] 访问
 * [机器人账号信息][BotAccount]: 可通过 [Bot.qqAccount] 访问
 *
 * 若需要得到机器人的 QQ 账号, 请访问 [Bot.qqAccount]
 * 若需要得到服务器上所有机器人列表, 请访问 [Bot.instances]
 *
 * 在 BotHelper.kt 中有一些访问的捷径. 如 [Bot.getGroup]
 *
 *
 *
 * Bot that is the base of the whole program.
 * It consists of
 * a [ContactSystem], which manage contacts such as [QQ] and [Group];
 * a [TIMBotNetworkHandler], which manages the connection to the server;
 * a [BotAccount], which stores the account information(e.g. qq number the bot)
 *
 * To of all the QQ contacts, access [Bot.qqAccount]
 * To of all the Robot instance, access [Bot.instances]
 *
 *
 * @author Him188moe
 * @author NaturalHG
 * @see Contact
 */
class Bot(val account: BotAccount, val logger: MiraiLogger, context: CoroutineContext) : CoroutineScope {
    private val supervisorJob = SupervisorJob(context[Job])
    override val coroutineContext: CoroutineContext =
        context + supervisorJob + CoroutineExceptionHandler { _, e -> e.logStacktrace("An exception was thrown under a coroutine of Bot") }

    constructor(qq: UInt, password: String, context: CoroutineContext) : this(BotAccount(qq, password), context)
    constructor(account: BotAccount, context: CoroutineContext) : this(account, DefaultLogger("Bot(" + account.id + ")"), context)

    val contacts = ContactSystem()

    lateinit var network: BotNetworkHandler<*>

    init {
        launch {
            addInstance(this@Bot)
        }
    }

    override fun toString(): String = "Bot(${account.id})"

    /**
     * [关闭][BotNetworkHandler.close]网络处理器, 取消所有运行在 [BotNetworkHandler] 下的协程.
     * 然后重新启动并尝试登录
     */
    @JvmOverloads // shouldn't be suspend!! This function MUST NOT inherit the context from the caller because the caller(NetworkHandler) is going to close
    fun tryReinitializeNetworkHandler(
        configuration: BotConfiguration,
        cause: Throwable? = null
    ): Job = launch {
        repeat(configuration.reconnectionRetryTimes) {
            if (reinitializeNetworkHandlerAsync(configuration, cause).await().isSuccess()) {
                logger.info("Reconnected successfully")
                return@launch
            } else {
                delay(configuration.reconnectPeriod.millisecondsLong)
            }
        }
    }

    /**
     * [关闭][BotNetworkHandler.close]网络处理器, 取消所有运行在 [BotNetworkHandler] 下的协程.
     * 然后重新启动并尝试登录
     */
    @JvmOverloads // shouldn't be suspend!! This function MUST NOT inherit the context from the caller because the caller(NetworkHandler) is going to close
    suspend fun reinitializeNetworkHandler(
        configuration: BotConfiguration,
        cause: Throwable? = null
    ): LoginResult {
        logger.info("Initializing BotNetworkHandler")
        try {
            if (::network.isInitialized) {
                network.close(cause)
            }
        } catch (e: Exception) {
            logger.error("Cannot close network handler", e)
        }
        network = TIMBotNetworkHandler(this@Bot.coroutineContext + configuration, this@Bot)

        return network.login()
    }

    /**
     * [关闭][BotNetworkHandler.close]网络处理器, 取消所有运行在 [BotNetworkHandler] 下的协程.
     * 然后重新启动并尝试登录
     */
    @JvmOverloads // shouldn't be suspend!! This function MUST NOT inherit the context from the caller because the caller(NetworkHandler) is going to close
    fun reinitializeNetworkHandlerAsync(
        configuration: BotConfiguration,
        cause: Throwable? = null
    ): Deferred<LoginResult> = async { reinitializeNetworkHandler(configuration, cause) }

    /**
     * Bot 联系人管理.
     *
     * @see Bot.contacts
     */
    inner class ContactSystem internal constructor() {
        val bot: Bot get() = this@Bot

        @Suppress("PropertyName")
        internal val _groups = MutableContactList<Group>()
        internal lateinit var groupsUpdater: Job
        private val groupsLock = Mutex()

        val groups: ContactList<Group> = ContactList(_groups)

        @Suppress("PropertyName")
        internal val _qqs = MutableContactList<QQ>() //todo 实现群列表和好友列表获取
        internal lateinit var qqUpdaterJob: Job
        private val qqsLock = Mutex()

        val qqs: ContactList<QQ> = ContactList(_qqs)

        /**
         * 获取缓存的 QQ 对象. 若没有对应的缓存, 则会创建一个.
         *
         * 注: 这个方法是线程安全的
         */
        suspend fun getQQ(id: UInt): QQ =
            if (_qqs.containsKey(id)) _qqs[id]!!
            else qqsLock.withLock {
                _qqs.getOrPut(id) { QQImpl(bot, id, coroutineContext) }
            }

        /**
         * 获取缓存的群对象. 若没有对应的缓存, 则会创建一个.
         *
         * 注: 这个方法是线程安全的
         */
        suspend fun getGroup(internalId: GroupInternalId): Group = getGroup(internalId.toId())

        /**
         * 获取缓存的群对象. 若没有对应的缓存, 则会创建一个.
         *
         * 注: 这个方法是线程安全的
         */
        suspend fun getGroup(id: GroupId): Group = id.value.let {
            if (_groups.containsKey(it)) _groups[it]!!
            else groupsLock.withLock {
                _groups.getOrPut(it) { Group(bot, id, coroutineContext) }
            }
        }
    }

    suspend inline fun Int.qq(): QQ = getQQ(this.coerceAtLeastOrFail(0).toUInt())
    suspend inline fun Long.qq(): QQ = getQQ(this.coerceAtLeastOrFail(0))
    suspend inline fun UInt.qq(): QQ = getQQ(this)

    suspend inline fun Int.group(): Group = getGroup(this.coerceAtLeastOrFail(0).toUInt())
    suspend inline fun Long.group(): Group = getGroup(this.coerceAtLeastOrFail(0))
    suspend inline fun UInt.group(): Group = getGroup(GroupId(this))
    suspend inline fun GroupId.group(): Group = getGroup(this)
    suspend inline fun GroupInternalId.group(): Group = getGroup(this)

    suspend fun close() {
        network.close()
        this.coroutineContext.cancelChildren()
        contacts._groups.clear()
        contacts._qqs.clear()
    }

    companion object {
        @Suppress("ObjectPropertyName")
        private val _instances: MutableList<Bot> = mutableListOf()
        private val instanceLock: Mutex = Mutex()

        private val instances: List<Bot> get() = _instances

        suspend fun instanceWhose(qq: UInt) = instanceLock.withLock {
            instances.first { it.qqAccount == qq }
        }

        internal suspend fun addInstance(bot: Bot) = instanceLock.withLock {
            _instances += bot
        }
    }
}