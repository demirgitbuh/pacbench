package com.demirarch.pacbench.metrics

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.annotation.Keep
import com.demirarch.pacbench.model.AccessMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizukuPermissionState {
    BINDER_UNAVAILABLE,
    PERMISSION_REQUIRED,
    DENIED,
    CONNECTING,
    READY,
}

class ShizukuCommandExecutor(
    context: Context,
    private val connectionTimeoutMillis: Long = 5_000,
) : CommandExecutor {
    override val accessMode: AccessMode = AccessMode.SHIZUKU

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val serviceArgs = Shizuku.UserServiceArgs(ComponentName(appContext, ShizukuCommandService::class.java))
        .daemon(false)
        .processNameSuffix("pacbench_metrics")
        .tag("pacbench-metrics-v1")
        .version(1)

    @Volatile
    var permissionState: ShizukuPermissionState = ShizukuPermissionState.BINDER_UNAVAILABLE
        private set

    @Volatile
    private var serviceBinder: IBinder? = null
    private var connectionWaiter: CompletableDeferred<IBinder?>? = null

    @Volatile
    private var deniedByUser = false

    @Volatile
    private var connectionFailure: String? = null

    @Volatile
    private var closed = false
    private var reconnectJob: Job? = null

    private val serviceDeathRecipient = IBinder.DeathRecipient {
        clearServiceBinder()
        connectionFailure = "Shizuku user service binder died"
        scheduleReconnect()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            serviceBinder = service
            val linked = runCatching { service.linkToDeath(serviceDeathRecipient, 0) }.isSuccess
            if (!linked || !service.isBinderAlive || closed) {
                clearServiceBinder()
                if (!closed) {
                    connectionFailure = "Shizuku user service binder was already dead"
                    scheduleReconnect()
                }
                return
            }
            connectionFailure = null
            permissionState = ShizukuPermissionState.READY
            synchronized(lock) {
                connectionWaiter?.complete(service)
                connectionWaiter = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            clearServiceBinder()
            connectionFailure = "Shizuku user service disconnected"
            scheduleReconnect()
        }

        override fun onBindingDied(name: ComponentName) {
            clearServiceBinder()
            connectionFailure = "Shizuku user service binding died"
            scheduleReconnect()
        }

        override fun onNullBinding(name: ComponentName) {
            clearServiceBinder()
            connectionFailure = "Shizuku returned a null user-service binding"
            permissionState = ShizukuPermissionState.BINDER_UNAVAILABLE
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        deniedByUser = false
        connectionFailure = null
        refreshPermissionState(connect = true)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        clearServiceBinder()
        connectionFailure = "Shizuku binder died"
        permissionState = ShizukuPermissionState.BINDER_UNAVAILABLE
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        deniedByUser = grantResult != PackageManager.PERMISSION_GRANTED
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            permissionState = ShizukuPermissionState.CONNECTING
            scheduleReconnect(delayMillis = 0)
        } else {
            clearServiceBinder()
            permissionState = ShizukuPermissionState.DENIED
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshPermissionState(connect = true)
    }

    fun requestPermission(): Boolean {
        if (closed) return false
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            permissionState = ShizukuPermissionState.BINDER_UNAVAILABLE
            return false
        }
        if (isShizukuGranted()) {
            refreshPermissionState(connect = true)
            return true
        }
        return runCatching {
            permissionState = ShizukuPermissionState.PERMISSION_REQUIRED
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }.getOrElse {
            permissionState = ShizukuPermissionState.BINDER_UNAVAILABLE
            false
        }
    }

    override suspend fun availability(): ExecutorAvailability {
        val binder = awaitService()
        return ExecutorAvailability(
            available = binder?.isBinderAlive == true,
            permissionDenied = permissionState == ShizukuPermissionState.DENIED ||
                permissionState == ShizukuPermissionState.PERMISSION_REQUIRED,
            reason = availabilityReason(),
        )
    }

    override suspend fun execute(command: SafeCommand): CommandResult {
        try {
            CommandPolicy.argv(command)
        } catch (error: IllegalArgumentException) {
            return CommandResult(CommandStatus.PERMISSION_DENIED, reason = error.message)
        }
        val binder = awaitService() ?: return CommandResult(
            status = if (
                permissionState == ShizukuPermissionState.DENIED ||
                permissionState == ShizukuPermissionState.PERMISSION_REQUIRED
            ) CommandStatus.PERMISSION_DENIED else CommandStatus.UNAVAILABLE,
            reason = availabilityReason(),
        )

        return withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SHIZUKU_SERVICE_DESCRIPTOR)
                writeCommand(data, command)
                if (!binder.transact(TRANSACTION_EXECUTE, data, reply, 0)) {
                    clearServiceBinder()
                    return@withContext CommandResult(CommandStatus.UNAVAILABLE, reason = "Shizuku service rejected transaction")
                }
                reply.readException()
                readResult(reply)
            } catch (error: SecurityException) {
                CommandResult(CommandStatus.PERMISSION_DENIED, reason = error.message ?: "Shizuku command denied")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                clearServiceBinder()
                connectionFailure = error.message ?: "Shizuku service disconnected"
                scheduleReconnect()
                CommandResult(CommandStatus.UNAVAILABLE, reason = error.message ?: "Shizuku service disconnected")
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        reconnectJob?.cancel()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, true) }
        }
        clearServiceBinder()
        permissionState = ShizukuPermissionState.BINDER_UNAVAILABLE
        scope.cancel()
    }

    private fun refreshPermissionState(connect: Boolean) {
        if (closed) return
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            permissionState = ShizukuPermissionState.BINDER_UNAVAILABLE
            return
        }
        if (!isShizukuGranted()) {
            permissionState = if (deniedByUser) {
                ShizukuPermissionState.DENIED
            } else {
                ShizukuPermissionState.PERMISSION_REQUIRED
            }
            return
        }
        permissionState = if (serviceBinder?.isBinderAlive == true) {
            ShizukuPermissionState.READY
        } else {
            ShizukuPermissionState.CONNECTING
        }
        if (connect && serviceBinder?.isBinderAlive != true) scheduleReconnect(delayMillis = 0)
    }

    private fun isShizukuGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private suspend fun awaitService(): IBinder? {
        if (closed) return null
        if (!isShizukuGranted()) {
            refreshPermissionState(connect = false)
            return null
        }
        serviceBinder?.takeIf { it.isBinderAlive }?.let { return it }

        var shouldBind = false
        val waiter = synchronized(lock) {
            connectionWaiter?.takeIf { it.isActive } ?: CompletableDeferred<IBinder?>().also {
                connectionWaiter = it
                shouldBind = true
            }
        }
        if (shouldBind) {
            permissionState = ShizukuPermissionState.CONNECTING
            val bound = withContext(Dispatchers.Main.immediate) {
                runCatching {
                    Shizuku.bindUserService(serviceArgs, serviceConnection)
                    true
                }.getOrDefault(false)
            }
            if (!bound) {
                synchronized(lock) {
                    waiter.complete(null)
                    if (connectionWaiter === waiter) connectionWaiter = null
                }
                connectionFailure = "Shizuku user service binding failed"
                permissionState = ShizukuPermissionState.BINDER_UNAVAILABLE
            }
        }
        val binder = withTimeoutOrNull(connectionTimeoutMillis) { waiter.await() }
        if (binder != null) return binder.takeIf { it.isBinderAlive }

        var timedOut = false
        synchronized(lock) {
            if (connectionWaiter === waiter && waiter.isActive) {
                waiter.complete(null)
                connectionWaiter = null
                timedOut = true
            }
        }
        if (timedOut) {
            connectionFailure = "Shizuku user service connection timed out after $connectionTimeoutMillis ms"
            withContext(Dispatchers.Main.immediate) {
                runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, false) }
            }
        }
        return null
    }

    private fun clearServiceBinder() {
        val old = serviceBinder
        serviceBinder = null
        if (old != null) runCatching { old.unlinkToDeath(serviceDeathRecipient, 0) }
        synchronized(lock) {
            connectionWaiter?.complete(null)
            connectionWaiter = null
        }
        if (permissionState == ShizukuPermissionState.READY) {
            permissionState = if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                ShizukuPermissionState.CONNECTING
            } else {
                ShizukuPermissionState.BINDER_UNAVAILABLE
            }
        }
    }

    private fun scheduleReconnect(delayMillis: Long = RECONNECT_DELAY_MILLIS) {
        if (closed || !isShizukuGranted()) return
        synchronized(lock) {
            if (reconnectJob?.isActive == true) return
            permissionState = ShizukuPermissionState.CONNECTING
            reconnectJob = scope.launch {
                if (delayMillis > 0) delay(delayMillis)
                repeat(MAX_RECONNECT_ATTEMPTS) { attempt ->
                    if (closed || !isShizukuGranted() || awaitService() != null) return@launch
                    if (attempt < MAX_RECONNECT_ATTEMPTS - 1) delay(RECONNECT_DELAY_MILLIS)
                }
                if (!closed && serviceBinder?.isBinderAlive != true) {
                    permissionState = ShizukuPermissionState.BINDER_UNAVAILABLE
                }
            }
        }
    }

    private fun availabilityReason(): String? {
        if (closed) return "Shizuku executor is closed"
        return when (permissionState) {
            ShizukuPermissionState.BINDER_UNAVAILABLE -> connectionFailure ?: "Shizuku binder is unavailable"
            ShizukuPermissionState.PERMISSION_REQUIRED -> "Shizuku permission has not been granted"
            ShizukuPermissionState.DENIED -> "Shizuku permission was denied"
            ShizukuPermissionState.CONNECTING -> connectionFailure ?: "Shizuku user service is connecting"
            ShizukuPermissionState.READY -> null
        }
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 0x504143
        const val RECONNECT_DELAY_MILLIS = 250L
        const val MAX_RECONNECT_ATTEMPTS = 3
    }
}

/** Runs inside Shizuku's user-service process. It accepts only the fixed command protocol below. */
@Keep
class ShizukuCommandService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(SHIZUKU_SERVICE_DESCRIPTOR)
            return true
        }
        if (code == USER_SERVICE_TRANSACTION_DESTROY) {
            reply?.writeNoException()
            kotlin.system.exitProcess(0)
        }
        if (code != TRANSACTION_EXECUTE) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(SHIZUKU_SERVICE_DESCRIPTOR)
        val result = try {
            val command = readCommand(data)
            kotlinx.coroutines.runBlocking { ProcessRunner.run(CommandPolicy.argv(command)) }
        } catch (error: IllegalArgumentException) {
            CommandResult(CommandStatus.PERMISSION_DENIED, reason = error.message)
        } catch (error: Exception) {
            CommandResult(CommandStatus.FAILED, reason = error.message ?: "Remote command failed")
        }
        reply?.writeNoException()
        reply?.let { writeResult(it, result) }
        return true
    }
}

private const val SHIZUKU_SERVICE_DESCRIPTOR = "com.demirarch.pacbench.metrics.IShizukuCommandService"
private const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
private const val USER_SERVICE_TRANSACTION_DESTROY = 16_777_115

private fun writeCommand(parcel: Parcel, command: SafeCommand) {
    when (command) {
        is SafeCommand.ReadFile -> {
            parcel.writeInt(1)
            parcel.writeString(command.path)
        }
        is SafeCommand.ListDirectory -> {
            parcel.writeInt(2)
            parcel.writeString(command.path)
        }
        SafeCommand.SurfaceFlingerLayers -> parcel.writeInt(3)
        is SafeCommand.SurfaceFlingerLatency -> {
            parcel.writeInt(4)
            parcel.writeString(command.layer)
        }
        is SafeCommand.GfxInfoFramestats -> {
            parcel.writeInt(5)
            parcel.writeString(command.packageName)
        }
    }
}

private fun readCommand(parcel: Parcel): SafeCommand = when (parcel.readInt()) {
    1 -> SafeCommand.ReadFile(requireNotNull(parcel.readString()))
    2 -> SafeCommand.ListDirectory(requireNotNull(parcel.readString()))
    3 -> SafeCommand.SurfaceFlingerLayers
    4 -> SafeCommand.SurfaceFlingerLatency(requireNotNull(parcel.readString()))
    5 -> SafeCommand.GfxInfoFramestats(requireNotNull(parcel.readString()))
    else -> throw IllegalArgumentException("Unknown command code")
}

private fun writeResult(parcel: Parcel, result: CommandResult) {
    parcel.writeInt(result.status.ordinal)
    parcel.writeInt(result.exitCode ?: Int.MIN_VALUE)
    parcel.writeString(result.stdout)
    parcel.writeString(result.stderr)
    parcel.writeString(result.reason)
}

private fun readResult(parcel: Parcel): CommandResult {
    val ordinal = parcel.readInt()
    require(ordinal in CommandStatus.entries.indices) { "Unknown command status" }
    val exitCode = parcel.readInt().takeUnless { it == Int.MIN_VALUE }
    return CommandResult(
        status = CommandStatus.entries[ordinal],
        exitCode = exitCode,
        stdout = parcel.readString().orEmpty(),
        stderr = parcel.readString().orEmpty(),
        reason = parcel.readString(),
    )
}
