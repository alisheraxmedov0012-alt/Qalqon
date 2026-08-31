package uz.faceguard.app.core.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uz.faceguard.app.domain.model.ScanMode

/** Why a scan started / stopped — surfaced in debug UI. */
enum class ScanTrigger { APP_OPENED, SCREEN_ON, INTERACTION, COOLDOWN_EXPIRED, STOPPED }

data class ScanEvent(
    val trigger: ScanTrigger,
    val at: Long = System.currentTimeMillis(),
)

/**
 * Event-driven scan scheduler. The camera only runs while a scan window is
 * open; the window closes on cooldown or when the foreground app is no longer
 * protected. Battery Saver lengthens cooldown and shortens the window;
 * Strict does the opposite. Low battery (if enabled in settings) forces
 * Battery Saver behavior regardless of the selected mode.
 */
class ScanScheduler(
    private val context: Context,
) {
    private val _mode = MutableStateFlow(ScanMode.BALANCED)
    val mode: StateFlow<ScanMode> = _mode

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _lastEvent = MutableStateFlow<ScanEvent?>(null)
    val lastEvent: StateFlow<ScanEvent?> = _lastEvent

    private val _cooldownRemaining = MutableStateFlow(0L)
    val cooldownRemaining: StateFlow<Long> = _cooldownRemaining

    private var scope: CoroutineScope? = null
    private var scanJob: Job? = null
    private var cooldownJob: Job? = null
    private var screenReceiver: BroadcastReceiver? = null

    private var lowBatteryBehaviorEnabled = true

    fun setMode(new: ScanMode) { _mode.value = new }
    fun setLowBatteryBehavior(enabled: Boolean) { lowBatteryBehaviorEnabled = enabled }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
        registerScreenReceiver()
    }

    fun detach() {
        scope = null
        unregisterScreenReceiver()
        stopScan(ScanTrigger.STOPPED)
    }

    /** Called when the foreground monitor reports a protected app. */
    fun onProtectedAppOpened() = requestScan(ScanTrigger.APP_OPENED)

    /** Called on meaningful interaction inside a protected app. */
    fun onInteraction() = requestScan(ScanTrigger.INTERACTION)

    /** Called when the screen turns on / device unlocks. */
    fun onScreenOn() = requestScan(ScanTrigger.SCREEN_ON)

    /** Effective mode after low-battery override. */
    fun effectiveMode(): ScanMode =
        if (lowBatteryBehaviorEnabled && isBatteryLow()) ScanMode.BATTERY_SAVER else _mode.value

    private fun isBatteryLow(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 1..19
    }

    private fun requestScan(trigger: ScanTrigger) {
        val scope = scope ?: return
        if (_scanning.value) return
        if (_cooldownRemaining.value > 0) return
        _lastEvent.value = ScanEvent(trigger)
        _scanning.value = true
        scanJob = scope.launch {
            val windowMs = scanWindowMs(effectiveMode())
            delay(windowMs)
            stopScan(ScanTrigger.COOLDOWN_EXPIRED)
        }
    }

    private fun stopScan(trigger: ScanTrigger) {
        if (!_scanning.value) return
        _scanning.value = false
        _lastEvent.value = ScanEvent(trigger)
        scanJob?.cancel()
        scanJob = null
        startCooldown()
    }

    private fun startCooldown() {
        val scope = scope ?: return
        val cooldownMs = cooldownMs(effectiveMode())
        cooldownJob?.cancel()
        cooldownJob = scope.launch {
            _cooldownRemaining.value = cooldownMs
            while (_cooldownRemaining.value > 0) {
                delay(1_000)
                _cooldownRemaining.value -= 1_000
            }
        }
    }

    private fun scanWindowMs(mode: ScanMode): Long = when (mode) {
        ScanMode.BATTERY_SAVER -> 3_000L
        ScanMode.BALANCED -> 5_000L
        ScanMode.STRICT -> 8_000L
    }

    private fun cooldownMs(mode: ScanMode): Long = when (mode) {
        ScanMode.BATTERY_SAVER -> 30_000L
        ScanMode.BALANCED -> 15_000L
        ScanMode.STRICT -> 5_000L
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) onScreenOn()
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        context.registerReceiver(receiver, filter)
        screenReceiver = receiver
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let { context.unregisterReceiver(it) }
        screenReceiver = null
    }
}
