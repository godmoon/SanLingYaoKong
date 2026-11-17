package com.openremo.data

import com.openremo.ble.RemoBleClient
import com.openremo.protocol.AcState
import com.openremo.protocol.CapabilityReport
import com.openremo.protocol.ControlPayload
import com.openremo.protocol.ControlFlags
import com.openremo.protocol.DeviceCapabilities
import com.openremo.protocol.L1Frame
import com.openremo.protocol.L1Protocol
import com.openremo.protocol.L2Protocol
import com.openremo.protocol.L2ReplyParser
import com.openremo.protocol.L3Protocol
import com.openremo.protocol.L3Reply
import com.openremo.protocol.LoginCodec
import com.openremo.protocol.StatusReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class ProtocolException(
    message: String,
    val code: Int? = null,
    val passwordError: Boolean = false,
) : Exception(message)

/** 登录握手的结果：同步读到的状态 + 机型能力表 */
data class HandshakeResult(
    val state: AcState?,
    val capabilities: DeviceCapabilities?,
)

private data class Command(
    val ctrl: Long,
    val func: Long,
    val payload: ByteArray = ByteArray(0),
)

private data class Group(
    val sessionId: Int,
    val commands: List<Command>,
)

private data class Job(val groups: List<Group>)

/**
 * L2/L3 会话层。
 *
 * 每连接一次都会重新走：
 *   连接确认 Job -> 登录 Job（含一次状态读取） -> 轮询/控制 Job
 *
 * 每个 Job 的帧序列：
 *   session 0 开始 -> 结束
 *   group N 开始 -> 若干条 L3 数据 -> 结束
 *   session 1 开始 -> 结束
 */
class ProtocolEngine(
    private val ble: RemoBleClient,
    private val onLog: (tag: String, message: String) -> Unit,
) {

    private companion object {
        const val TIMEOUT_MS = 6_000L
        const val MAX_RETRY = 3

        const val SESSION_JOB_START = 0
        const val SESSION_JOB_END = 1
        const val SESSION_LOGIN_A = 3
        const val SESSION_LOGIN_B = 4
        const val SESSION_CONFIRM = 2
        const val SESSION_CONTROL = 4

        const val CTRL_READ = 0L
        const val CTRL_WRITE = 1L
        const val CTRL_STATUS = 2L

        const val FUNC_STATUS = 0L
        const val FUNC_CONTROL = 1L
    }

    private val mutex = Mutex()
    private val incoming = Channel<L1Frame>(Channel.UNLIMITED)

    @Volatile
    private var seq = 0
    private var guestLogin = true
    private var loginPassword = ""

    /** 登录块里的会话 ID 必须跟随当前 L2 会话，不能写死。 */
    private fun loginFor(sessionId: Int): ByteArray =
        if (guestLogin) LoginCodec.guest(sessionId) else LoginCodec.user(sessionId, loginPassword)

    fun onFrame(frame: L1Frame) {
        incoming.trySend(frame)
    }

    fun resetSequence() {
        seq = 0
        while (incoming.tryReceive().isSuccess) {
            // 丢弃旧连接遗留的帧
        }
    }

    /**
     * @return 登录时同步读到的状态和机型能力表；设备未返回时对应字段可为 null
     */
    suspend fun handshake(guest: Boolean, password: String): HandshakeResult = mutex.withLock {
        // 先校验密码格式，避免连接成功后才发现参数错误
        if (!guest && !LoginCodec.isValidPassword(password)) {
            throw ProtocolException(
                "密码格式不正确，请输入 4 位十六进制字符（0-9、A-F）",
                passwordError = true,
            )
        }
        guestLogin = guest
        loginPassword = password.trim().uppercase(Locale.US)
        resetSequence()

        onLog("协议", "开始连接确认")
        runJobLocked(
            Job(
                listOf(
                    Group(
                        SESSION_CONFIRM,
                        listOf(Command(CTRL_WRITE, FUNC_STATUS, byteArrayOf(0x01))),
                    )
                )
            )
        )

        onLog("协议", "登录中（${if (guest) "访客" else "用户密码"}）")
        // 与原 SDK 的登录 Job 一致：会话 3 读一次设备能力（j.b），会话 4 读状态（o.a）。
        // 能力读和状态读的多余 func（时间/静态参数）由设备能力位决定，
        // 40 系列机型不支持，发了会回 L3 码 89 导致整个登录失败，这里不发。
        val replies = runJobLocked(
            Job(
                listOf(
                    Group(
                        SESSION_LOGIN_A,
                        listOf(Command(CTRL_READ, FUNC_STATUS)),
                    ),
                    Group(
                        SESSION_LOGIN_B,
                        listOf(Command(CTRL_STATUS, FUNC_STATUS)),
                    ),
                )
            )
        )
        onLog("协议", "登录成功")
        val capability = replies
            .firstOrNull { it.ctrl == CTRL_READ && it.func == FUNC_STATUS && it.success }
            ?.let { CapabilityReport.parse(it.payload) }
        if (capability == null) {
            onLog("协议", "未读到机型能力表，控件按全量显示")
        } else {
            onLog(
                "协议",
                "机型能力：模式(cool=${capability.coolMode}, heat=${capability.heatMode}, " +
                    "dry=${capability.dryMode}, fan=${capability.fanMode}, auto=${capability.autoMode})，" +
                    "vane=${capability.vaneControl}, louver=${capability.louver}, " +
                    "rightLeft=${capability.rightLeftControl}, moveEye=${capability.moveEye}, " +
                    "hold=${capability.holdEnabled}, innerClean=${capability.innerClean}, " +
                    "lossnay=${capability.lossnay}, fanSteps=${capability.fanSteps}",
            )
        }
        val state = replies
            .firstOrNull { it.ctrl == CTRL_STATUS && it.func == FUNC_STATUS }
            ?.let { StatusReport.parse(it.payload) }
        HandshakeResult(state, capability)
    }

    suspend fun readState(): AcState? = mutex.withLock {
        val replies = runJobLocked(
            Job(
                listOf(
                    Group(
                        SESSION_CONTROL,
                        listOf(Command(CTRL_STATUS, FUNC_STATUS)),
                    )
                )
            )
        )
        replies.firstOrNull { it.ctrl == CTRL_STATUS && it.func == FUNC_STATUS }
            ?.let { StatusReport.parse(it.payload) }
    }

    suspend fun control(state: AcState, flags: ControlFlags, vaneControl: Int = 0): AcState? = mutex.withLock {
        val payload = ControlPayload.build(state, flags, vaneControl)
        val replies = runJobLocked(
            Job(
                listOf(
                    Group(
                        SESSION_CONTROL,
                        listOf(
                            Command(CTRL_WRITE, FUNC_CONTROL, payload),
                            Command(CTRL_STATUS, FUNC_STATUS),
                        ),
                    )
                )
            )
        )
        // 写控制命令的回执码非 0 表示设备拒绝了这次设置（例如该机型不支持风向/百叶），
        // 先把拒绝原因抛给界面，紧接着的状态读也会把界面纠正回真实值
        val writeReply = replies.firstOrNull { it.ctrl == CTRL_WRITE && it.func == FUNC_CONTROL }
        if (writeReply != null && !writeReply.success) {
            throw ProtocolException(
                "设备拒绝了这次设置（返回码 ${writeReply.code}），该机型可能不支持此项",
                writeReply.code,
            )
        }
        replies.firstOrNull { it.ctrl == CTRL_STATUS && it.func == FUNC_STATUS }
            ?.let { StatusReport.parse(it.payload) }
    }

    private suspend fun runJobLocked(job: Job): List<L3Reply> = runJobOnce(job)

    private suspend fun runJobOnce(job: Job): List<L3Reply> {
        val replies = ArrayList<L3Reply>()

        startSession(SESSION_JOB_START)
        endSession(SESSION_JOB_START)

        for (group in job.groups) {
            startSession(group.sessionId)
            for (command in group.commands) {
                replies.add(sendCommand(command))
            }
            endSession(group.sessionId)
        }

        startSession(SESSION_JOB_END)
        endSession(SESSION_JOB_END)
        return replies
    }

    private suspend fun startSession(sessionId: Int) {
        val reply = transact(L2Protocol.buildStart(sessionId, loginFor(sessionId)))
        checkSessionReply(reply, "开始会话 $sessionId")
    }

    private suspend fun endSession(sessionId: Int) {
        val reply = transact(L2Protocol.buildEnd(sessionId, loginFor(sessionId)))
        checkSessionReply(reply, "结束会话 $sessionId")
    }

    private fun checkSessionReply(reply: com.openremo.protocol.L2Reply, action: String) {
        // 开始/结束会话只看 L2 响应码；响应体里的 L3 字段只是回显，不是错误码
        checkL2(reply.l2Code, action)
    }

    private suspend fun sendCommand(command: Command): L3Reply {
        val l3 = L3Protocol.build(command.ctrl, command.func, command.payload)
        val reply = transact(L2Protocol.buildData(l3))
        checkL2(reply.l2Code, "发送命令")
        val parsed = L3Protocol.parse(reply.body)
            ?: throw ProtocolException("L3 响应格式不正确")
        if (!parsed.success) {
            if (parsed.code == 10) {
                throw ProtocolException("密码错误", parsed.code, passwordError = true)
            }
            // 原 SDK 对不认识的命令回码只提示，不会中断整个任务；
            // 例如 40 系列不支持的能力查询会回 89，登录流程要能继续
            onLog("协议", "命令 func=${command.func} 返回码 ${parsed.code}，忽略")
        }
        return parsed
    }

    private suspend fun transact(l2Payload: ByteArray): com.openremo.protocol.L2Reply {
        var attempt = 0
        while (true) {
            if (!ble.isConnected()) {
                throw ProtocolException("链路已断开")
            }
            val expected = seq
            drainIncoming()
            val frame = L1Protocol.build(expected, l2Payload)
            onLog("TX", toHex(frame))
            ble.write(frame)

            val received = withTimeoutOrNull(TIMEOUT_MS) { awaitMatching(expected) }
            if (received == null) {
                attempt++
                onLog("协议", "等待响应超时（第 $attempt 次）")
                if (attempt >= MAX_RETRY) {
                    throw ProtocolException("设备无响应，请检查距离后重试")
                }
                delay(120)
                continue
            }

            onLog("RX", toHex(received.raw))
            seq = (seq + 1) and 0x07
            val reply = L2ReplyParser.parse(received.frame.payload)
                ?: throw ProtocolException("L2 响应格式不正确")

            when (reply.l2Code) {
                0 -> return reply
                8 -> {
                    attempt++
                    onLog("协议", "设备要求重发（$attempt）")
                    if (attempt >= MAX_RETRY) {
                        throw ProtocolException("设备持续要求重发")
                    }
                    continue
                }

                2 -> throw ProtocolException("设备要求重新开始任务，正在重连", 2)
                9 -> throw ProtocolException("设备内部错误")
                10 -> throw ProtocolException("密码错误", 10, passwordError = true)
                else -> throw ProtocolException("L2 错误码 ${reply.l2Code}", reply.l2Code)
            }
        }
    }

    private data class Received(val frame: L1Frame, val raw: ByteArray)

    private suspend fun awaitMatching(expectedSeq: Int): Received {
        while (true) {
            if (!ble.isConnected()) {
                throw ProtocolException("链路已断开")
            }
            // 分段等待，这样链路断开时能立刻退出，不用白等 6 秒
            val item = withTimeoutOrNull(200) { incoming.receive() } ?: continue
            if (item.seq != expectedSeq) {
                continue
            }
            return Received(item, item.raw)
        }
    }

    private fun drainIncoming() {
        while (incoming.tryReceive().isSuccess) {
            // drain
        }
    }

    private fun checkL2(code: Int, action: String) {
        if (code == 0) return
        if (code == 10) throw ProtocolException("密码错误", code, passwordError = true)
        throw ProtocolException("$action 失败（L2 错误码 $code）", code)
    }

    private fun toHex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "(空)"
        val sb = StringBuilder(bytes.size * 3)
        bytes.forEach {
            val v = it.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v)).append(' ')
        }
        return sb.toString().trim()
    }
}
