package com.mahor.lanremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

data class HopTarget(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    var smoothedRssi: Double = -90.0,
    var lastSeen: Long = 0L
)

/**
 * Custom vector emblem: Air-Hop swift/hare silhouette with gradient aura (zero image files).
 */
class AirHopLogoView(context: Context) : View(context) {
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (88 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w * 0.28f

        // Squircle base with tech gradient
        val bgShader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.parseColor("#1A1633"), Color.parseColor("#0D1322")),
            null,
            Shader.TileMode.CLAMP
        )
        auraPaint.shader = bgShader
        canvas.drawRoundRect(0f, 0f, w, h, r, r, auraPaint)

        // Ambient glow
        val glow = RadialGradient(
            w * 0.65f, h * 0.30f, w * 0.65f,
            Color.parseColor("#667C3AED"), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glow }
        canvas.drawRoundRect(0f, 0f, w, h, r, r, glowPaint)

        // Animal metaphor: streamlined air-leaping hare/swift silhouette
        val animalShader = LinearGradient(
            w * 0.2f, h * 0.75f, w * 0.85f, h * 0.2f,
            intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#A855F7"), Color.parseColor("#A3E635")),
            null,
            Shader.TileMode.CLAMP
        )
        bodyPaint.shader = animalShader

        val leapPath = Path().apply {
            moveTo(w * 0.22f, h * 0.66f)
            quadTo(w * 0.36f, h * 0.44f, w * 0.56f, h * 0.46f)
            // Swept-back ear / air-fin
            lineTo(w * 0.44f, h * 0.20f)
            lineTo(w * 0.58f, h * 0.38f)
            // Second dynamic ear
            lineTo(w * 0.54f, h * 0.17f)
            lineTo(w * 0.66f, h * 0.41f)
            // Head & snout hopping forward
            quadTo(w * 0.80f, h * 0.45f, w * 0.78f, h * 0.57f)
            quadTo(w * 0.66f, h * 0.62f, w * 0.52f, h * 0.64f)
            quadTo(w * 0.36f, h * 0.76f, w * 0.22f, h * 0.66f)
            close()
        }
        canvas.drawPath(leapPath, bodyPaint)

        // Hop motion arc below
        accentPaint.style = Paint.Style.STROKE
        accentPaint.strokeWidth = w * 0.032f
        accentPaint.strokeCap = Paint.Cap.ROUND
        accentPaint.color = Color.parseColor("#9938BDF8")
        canvas.drawArc(w * 0.24f, h * 0.64f, w * 0.74f, h * 0.86f, 15f, 130f, false, accentPaint)

        // Crisp eye dot
        eyePaint.color = Color.parseColor("#0B0D12")
        canvas.drawCircle(w * 0.67f, h * 0.49f, w * 0.036f, eyePaint)
    }
}

class MainActivity : AppCompatActivity() {

    // --- UI Theme Tokens (Edit here to restyle colors/radii easily) ---
    private val bgDark = "#090B10"
    private val cardDark = "#121622"
    private val borderGlow = "#2A3348"
    private val accentViolet = "#8B5CF6"
    private val accentCyan = "#38BDF8"
    private val accentLime = "#A3E635"
    private val textPrimary = "#F8FAFC"
    private val textMuted = "#94A3B8"

    private val devices = mutableMapOf<String, HopTarget>()
    private var activeDevice: HopTarget? = null
    private var isAutoMode = true

    private lateinit var statusLabel: TextView
    private lateinit var signalLabel: TextView
    private lateinit var modeBtn: Button
    private lateinit var deviceSpinner: Spinner
    private lateinit var spinnerAdapter: ArrayAdapter<String>
    private val deviceLabels = mutableListOf<String>()
    private val deviceIds = mutableListOf<String>()

    private val udpExecutor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seedHomeNodes()
        setContentView(createAirHopScreen())
        requestBlePermissions()
    }

    private fun seedHomeNodes() {
        val now = System.currentTimeMillis()
        devices["LIVING_TV"] = HopTarget("LIVING_TV", "Living Room TV", "192.168.31.101", 9876, -82.0, now)
        devices["BEDROOM_PC"] = HopTarget("BEDROOM_PC", "Workstation PC", "192.168.31.102", 9876, -86.0, now)
        activeDevice = devices["LIVING_TV"]
    }

    private fun createAirHopScreen(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bgDark))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 56, 44, 56)
        }

        // 1. Header Card with AIRHOP Vector Emblem + Gradient Accent
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 28, 32, 28)
            background = gradientCard(
                intArrayOf(Color.parseColor("#1E1736"), Color.parseColor("#10192B")),
                36f,
                Color.parseColor("#668B5CF6")
            )
        }
        headerCard.addView(AirHopLogoView(this))

        val brandCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 0, 0, 0)
        }
        brandCol.addView(TextView(this).apply {
            text = "AIRHOP"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f
            setTextColor(Color.parseColor(textPrimary))
        })
        brandCol.addView(TextView(this).apply {
            text = "PROXIMITY LAN CONTROL"
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(Color.parseColor(accentCyan))
        })
        signalLabel = TextView(this).apply {
            text = "${activeDevice?.name} • ${activeDevice?.ip}"
            textSize = 13f
            setTextColor(Color.parseColor(textMuted))
            setPadding(0, 10, 0, 0)
        }
        brandCol.addView(signalLabel)
        headerCard.addView(brandCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(headerCard)

        // 2. Auto / Manual Mode & Device Selector Card
        val modeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = gradientCard(
                intArrayOf(Color.parseColor(cardDark), Color.parseColor("#0E121C")),
                32f,
                Color.parseColor(borderGlow)
            )
        }
        val modeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 24, 0, 24) }
        container.addView(modeCard, modeParams)

        statusLabel = TextView(this).apply {
            text = "AUTO PROXIMITY ACTIVE • HYSTERESIS +8 dBm"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(accentLime))
            setPadding(0, 0, 0, 16)
        }
        modeCard.addView(statusLabel)

        modeBtn = Button(this).apply {
            text = "AUTO DETECT: ON (TAP FOR MANUAL)"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#090B10"))
            setPadding(24, 24, 24, 24)
            background = pillButton(accentCyan, 24f)
            setOnClickListener {
                isAutoMode = !isAutoMode
                syncModeUi()
                if (isAutoMode) evaluateProximity()
            }
        }
        modeCard.addView(modeBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, deviceLabels)
        deviceSpinner = Spinner(this).apply {
            adapter = spinnerAdapter
            setBackgroundColor(Color.parseColor("#192030"))
            setPadding(18, 18, 18, 18)
        }
        refreshDevices()

        val lockBtn = Button(this).apply {
            text = "LOCK"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(28, 20, 28, 20)
            background = pillButton(accentViolet, 20f)
            setOnClickListener {
                val idx = deviceSpinner.selectedItemPosition
                if (idx in deviceIds.indices) {
                    isAutoMode = false
                    activeDevice = devices[deviceIds[idx]]
                    syncModeUi()
                    updateTelemetry()
                }
            }
        }

        val selectorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 0)
            addView(deviceSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 16, 0)
            })
            addView(lockBtn)
        }
        modeCard.addView(selectorRow)

        // 3. Tactile Remote Control Grid
        val padCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 28)
            background = gradientCard(
                intArrayOf(Color.parseColor(cardDark), Color.parseColor("#0C0F18")),
                36f,
                Color.parseColor(borderGlow)
            )
        }
        container.addView(padCard)

        val keyRows = listOf(
            listOf("PWR" to "KEY_POWER", "MUTE" to "KEY_MUTE"),
            listOf("▲" to "KEY_UP"),
            listOf("◀" to "KEY_LEFT", "HOP OK" to "KEY_ENTER", "▶" to "KEY_RIGHT"),
            listOf("▼" to "KEY_DOWN"),
            listOf("BACK" to "KEY_BACK", "HOME" to "KEY_HOME"),
            listOf("VOL -" to "KEY_VOLDOWN", "PLAY/PAUSE" to "KEY_PLAYPAUSE", "VOL +" to "KEY_VOLUP")
        )

        for (row in keyRows) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 10)
            }
            for ((label, cmd) in row) {
                val isOk = cmd == "KEY_ENTER"
                val keyBtn = Button(this).apply {
                    text = label
                    textSize = if (isOk) 14f else 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (isOk) Color.parseColor("#090B10") else Color.parseColor(textPrimary))
                    setPadding(24, 30, 24, 30)
                    background = if (isOk) {
                        pillButton(accentLime, 26f)
                    } else {
                        gradientCard(
                            intArrayOf(Color.parseColor("#1A2234"), Color.parseColor("#131926")),
                            24f,
                            Color.parseColor("#334155")
                        )
                    }
                    setOnClickListener { dispatchLanCommand(cmd) }
                }
                rowView.addView(keyBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(10, 0, 10, 0)
                })
            }
            padCard.addView(rowView)
        }

        scroll.addView(container)
        return scroll
    }

    private fun gradientCard(colors: IntArray, radius: Float, strokeColor: Int) = GradientDrawable().apply {
        this.colors = colors
        orientation = GradientDrawable.Orientation.TL_BR
        cornerRadius = radius
        setStroke(2, strokeColor)
    }

    private fun pillButton(colorHex: String, radius: Float) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius
    }

    private fun syncModeUi() {
        if (isAutoMode) {
            modeBtn.text = "AUTO DETECT: ON (TAP FOR MANUAL)"
            modeBtn.background = pillButton(accentCyan, 24f)
            statusLabel.text = "AUTO PROXIMITY ACTIVE • HYSTERESIS +8 dBm"
            statusLabel.setTextColor(Color.parseColor(accentLime))
        } else {
            modeBtn.text = "MANUAL LOCK: ON (TAP FOR AUTO)"
            modeBtn.background = pillButton(accentViolet, 24f)
            statusLabel.text = "MANUAL OVERRIDE LOCKED"
            statusLabel.setTextColor(Color.parseColor("#C084FC"))
        }
    }

    private fun dispatchLanCommand(cmd: String) {
        val target = activeDevice ?: return
        udpExecutor.execute {
            try {
                val socket = DatagramSocket()
                val payload = "AIRHOP|${target.id}|$cmd".toByteArray(StandardCharsets.UTF_8)
                val packet = DatagramPacket(payload, payload.size, InetAddress.getByName(target.ip), target.port)
                socket.send(packet)
                socket.close()
                uiHandler.post {
                    signalLabel.text = "${target.name} • Sent $cmd"
                }
            } catch (e: Exception) {
                uiHandler.post {
                    Toast.makeText(this, "LAN send failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 202)
        } else {
            startBleProximityScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 202 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBleProximityScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleProximityScan() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bm.adapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanner.startScan(null, settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advName = result.device.name ?: result.scanRecord?.deviceName ?: return
                // Format: AIRHOP|ID|192.168.31.105|9876
                if (advName.startsWith("AIRHOP|")) {
                    val parts = advName.split("|")
                    if (parts.size == 4) {
                        val id = parts[1]
                        val ip = parts[2]
                        val port = parts[3].toIntOrNull() ?: 9876
                        val now = System.currentTimeMillis()
                        val node = devices.getOrPut(id) {
                            HopTarget(id, id, ip, port, result.rssi.toDouble(), now)
                        }
                        node.smoothedRssi = (0.35 * result.rssi) + (0.65 * node.smoothedRssi)
                        node.lastSeen = now
                        refreshDevices()
                        if (isAutoMode) evaluateProximity()
                    }
                }
            }
        })
    }

    private fun evaluateProximity() {
        val now = System.currentTimeMillis()
        val strongest = devices.values.filter { now - it.lastSeen < 5000 }.maxByOrNull { it.smoothedRssi } ?: return
        val current = activeDevice

        if (current == null || (strongest.id != current.id && strongest.smoothedRssi > current.smoothedRssi + 8.0)) {
            activeDevice = strongest
            updateTelemetry()
        }
    }

    private fun updateTelemetry() {
        uiHandler.post {
            val dev = activeDevice ?: return@post
            signalLabel.text = "${dev.name} • ${dev.ip} (${dev.smoothedRssi.toInt()} dBm)"
        }
    }

    private fun refreshDevices() {
        uiHandler.post {
            deviceLabels.clear()
            deviceIds.clear()
            devices.values.forEach {
                deviceLabels.add("${it.name} (${it.ip})")
                deviceIds.add(it.id)
            }
            spinnerAdapter.notifyDataSetChanged()
        }
    }
}
