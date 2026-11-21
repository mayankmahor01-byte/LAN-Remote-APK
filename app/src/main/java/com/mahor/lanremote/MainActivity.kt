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
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
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

data class HopDevice(
    val id: String,
    val label: String,
    val ip: String,
    val port: Int,
    var rssi: Double = -88.0,
    var lastSeen: Long = 0L
)

private object AirHopPalette {
    val voidBg = Color.parseColor("#06070B")
    val glassFill = Color.parseColor("#CC111520")
    val glassSubtle = Color.parseColor("#99141926")
    val rimLight = Color.parseColor("#38FFFFFF")
    val rimSoft = Color.parseColor("#1AFFFFFF")

    val glowViolet = Color.parseColor("#8B5CF6")
    val glowCyan = Color.parseColor("#22D3EE")
    val glowLime = Color.parseColor("#A3E635")
    val glowAmber = Color.parseColor("#F59E0B")

    val inkPrimary = Color.parseColor("#F8FAFC")
    val inkSecondary = Color.parseColor("#94A3B8")
}

/**
 * Bold squircle + leaping hare/swift glyph (zero external image files).
 */
class AirHopGlyphView(context: Context) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val px = (76 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(px, px)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = w * 0.27f

        bgPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(AirHopPalette.glowViolet, AirHopPalette.glowCyan),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, bgPaint)

        bloomPaint.shader = RadialGradient(
            w * 0.25f, h * 0.20f, w * 0.75f,
            Color.parseColor("#55FFFFFF"), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, bloomPaint)

        // Minimal high-contrast leaping animal silhouette in dark obsidian
        glyphPaint.color = Color.parseColor("#090B10")
        val silhouette = Path().apply {
            moveTo(w * 0.24f, h * 0.68f)
            quadTo(w * 0.36f, h * 0.45f, w * 0.57f, h * 0.48f)
            lineTo(w * 0.47f, h * 0.21f)
            lineTo(w * 0.60f, h * 0.41f)
            lineTo(w * 0.58f, h * 0.19f)
            lineTo(w * 0.68f, h * 0.44f)
            quadTo(w * 0.80f, h * 0.50f, w * 0.76f, h * 0.61f)
            quadTo(w * 0.62f, h * 0.66f, w * 0.48f, h * 0.68f)
            quadTo(w * 0.34f, h * 0.76f, w * 0.24f, h * 0.68f)
            close()
        }
        canvas.drawPath(silhouette, glyphPaint)

        // Crisp eye highlight
        cutPaint.color = AirHopPalette.glowLime
        canvas.drawCircle(w * 0.67f, h * 0.52f, w * 0.036f, cutPaint)
    }
}

class MainActivity : AppCompatActivity() {

    private val nodes = linkedMapOf<String, HopDevice>()
    private var currentTarget: HopDevice? = null
    private var autoProximity = true

    private lateinit var activeDeviceText: TextView
    private lateinit var telemetryText: TextView
    private lateinit var modePillBtn: Button
    private lateinit var targetSpinner: Spinner
    private lateinit var spinnerAdapter: ArrayAdapter<String>
    private val spinnerItems = mutableListOf<String>()
    private val spinnerIds = mutableListOf<String>()

    private val netExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seedNodes()
        setContentView(buildAirHopRoot())
        ensureBlePermissions()
    }

    private fun seedNodes() {
        val t = System.currentTimeMillis()
        nodes["LIVING_TV"] = HopDevice("LIVING_TV", "Living Room TV", "192.168.31.101", 9876, -80.0, t)
        nodes["WORK_PC"] = HopDevice("WORK_PC", "Desktop PC", "192.168.31.102", 9876, -87.0, t)
        currentTarget = nodes["LIVING_TV"]
    }

    private fun buildAirHopRoot(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(AirHopPalette.voidBg)
            isFillViewport = true
        }

        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 52, 40, 64)
        }

        stack.addView(buildHeroBloomCard())
        stack.addView(buildProximityCapsuleCard(), marginTopDp(18))
        stack.addView(buildRemoteClusterCard(), marginTopDp(18))

        scroll.addView(stack)
        return scroll
    }

    private fun buildHeroBloomCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 26, 28, 26)
            background = ambientGlowDrawable(
                bloomColor = AirHopPalette.glowViolet,
                surfaceColor = AirHopPalette.glassFill,
                strokeColor = AirHopPalette.rimLight,
                radiusPx = 44f
            )
        }

        card.addView(AirHopGlyphView(this))

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 0, 0)
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        brandRow.addView(TextView(this).apply {
            text = "AIRHOP"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.16f
            setTextColor(AirHopPalette.inkPrimary)
        })

        brandRow.addView(TextView(this).apply {
            text = "  LAN • BLE"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(AirHopPalette.glowCyan)
        })
        infoCol.addView(brandRow)

        activeDeviceText = TextView(this).apply {
            text = currentTarget?.let { "${it.label} · ${it.ip}" } ?: "No target"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AirHopPalette.inkPrimary)
            setPadding(0, 8, 0, 4)
        }
        infoCol.addView(activeDeviceText)

        telemetryText = TextView(this).apply {
            text = "AUTO HOP (+8 dBm) · PORT 9876"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(AirHopPalette.glowLime)
        }
        infoCol.addView(telemetryText)

        card.addView(infoCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    private fun buildProximityCapsuleCard(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 24, 26, 24)
            background = ambientGlowDrawable(
                bloomColor = AirHopPalette.glowCyan,
                surfaceColor = AirHopPalette.glassSubtle,
                strokeColor = AirHopPalette.rimSoft,
                radiusPx = 38f
            )
        }

        val topPills = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        modePillBtn = Button(this).apply {
            text = "AUTO DETECT : ON"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AirHopPalette.voidBg)
            setPadding(24, 18, 24, 18)
            background = solidPill(AirHopPalette.glowLime, 999f)
            setOnClickListener {
                autoProximity = !autoProximity
                refreshModeBadge()
                if (autoProximity) pickNearestNode()
            }
        }
        topPills.addView(modePillBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(topPills)

        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spinnerItems)
        targetSpinner = Spinner(this).apply {
            adapter = spinnerAdapter
            setBackgroundColor(Color.parseColor("#1D2436"))
            setPadding(18, 16, 18, 16)
        }
        syncSpinner()

        val manualLockBtn = Button(this).apply {
            text = "MANUAL LOCK"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AirHopPalette.inkPrimary)
            setPadding(24, 18, 24, 18)
            background = solidPill(Color.parseColor("#252D42"), 999f)
            setOnClickListener {
                val pos = targetSpinner.selectedItemPosition
                if (pos in spinnerIds.indices) {
                    autoProximity = false
                    currentTarget = nodes[spinnerIds[pos]]
                    refreshModeBadge()
                    refreshTelemetry()
                }
            }
        }

        val manualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 18, 0, 0)
            addView(targetSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 14, 0)
            })
            addView(manualLockBtn)
        }
        box.addView(manualRow)
        return box
    }

    private fun buildRemoteClusterCard(): View {
        val deck = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 26, 24, 26)
            background = ambientGlowDrawable(
                bloomColor = AirHopPalette.glowViolet,
                surfaceColor = AirHopPalette.glassFill,
                strokeColor = AirHopPalette.rimSoft,
                radiusPx = 42f
            )
        }

        val rows = listOf(
            listOf(
                KeySpec("POWER", "KEY_POWER", AirHopPalette.glowAmber, true),
                KeySpec("MUTE", "KEY_MUTE", null, false)
            ),
            listOf(
                KeySpec("▲", "KEY_UP", null, false)
            ),
            listOf(
                KeySpec("◀", "KEY_LEFT", null, false),
                KeySpec("OK", "KEY_ENTER", AirHopPalette.glowCyan, true),
                KeySpec("▶", "KEY_RIGHT", null, false)
            ),
            listOf(
                KeySpec("▼", "KEY_DOWN", null, false)
            ),
            listOf(
                KeySpec("BACK", "KEY_BACK", null, false),
                KeySpec("HOME", "KEY_HOME", null, false)
            ),
            listOf(
                KeySpec("VOL −", "KEY_VOLDOWN", null, false),
                KeySpec("PLAY / PAUSE", "KEY_PLAYPAUSE", AirHopPalette.glowViolet, true),
                KeySpec("VOL +", "KEY_VOLUP", null, false)
            )
        )

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 9, 0, 9)
            }
            for (spec in row) {
                val btn = Button(this).apply {
                    text = spec.title
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.06f
                    if (spec.highlightHex != null) {
                        setTextColor(if (spec.darkText) AirHopPalette.voidBg else AirHopPalette.inkPrimary)
                        background = solidPill(spec.highlightHex, 28f)
                    } else {
                        setTextColor(AirHopPalette.inkPrimary)
                        background = glassKeyDrawable()
                    }
                    setPadding(20, 28, 20, 28)
                    setOnClickListener { sendUdpCommand(spec.command) }
                }
                rowLayout.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(9, 0, 9, 0)
                })
            }
            deck.addView(rowLayout)
        }

        return deck
    }

    private data class KeySpec(
        val title: String,
        val command: String,
        val highlightHex: Int?,
        val darkText: Boolean
    )

    private fun ambientGlowDrawable(
        bloomColor: Int,
        surfaceColor: Int,
        strokeColor: Int,
        radiusPx: Float
    ): LayerDrawable {
        val base = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = radiusPx
            setStroke(2, strokeColor)
        }
        val bloom = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 420f
            setGradientCenter(0.18f, 0.12f)
            colors = intArrayOf(
                Color.argb(68, Color.red(bloomColor), Color.green(bloomColor), Color.blue(bloomColor)),
                Color.TRANSPARENT
            )
            cornerRadius = radiusPx
        }
        return LayerDrawable(arrayOf(base, bloom))
    }

    private fun glassKeyDrawable() = GradientDrawable().apply {
        colors = intArrayOf(Color.parseColor("#241B293D"), Color.parseColor("#24101522"))
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        cornerRadius = 28f
        setStroke(2, AirHopPalette.rimSoft)
    }

    private fun solidPill(colorInt: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(colorInt)
        cornerRadius = radiusPx
    }

    private fun marginTopDp(dp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = (dp * resources.displayMetrics.density).toInt()
    }

    private fun refreshModeBadge() {
        if (autoProximity) {
            modePillBtn.text = "AUTO DETECT : ON"
            modePillBtn.setTextColor(AirHopPalette.voidBg)
            modePillBtn.background = solidPill(AirHopPalette.glowLime, 999f)
            telemetryText.text = "AUTO HOP (+8 dBm) · PORT 9876"
            telemetryText.setTextColor(AirHopPalette.glowLime)
        } else {
            modePillBtn.text = "MANUAL LOCK : ON"
            modePillBtn.setTextColor(AirHopPalette.inkPrimary)
            modePillBtn.background = solidPill(AirHopPalette.glowViolet, 999f)
            telemetryText.text = "MANUAL OVERRIDE · AUTO PAUSED"
            telemetryText.setTextColor(AirHopPalette.glowCyan)
        }
    }

    private fun sendUdpCommand(command: String) {
        val target = currentTarget ?: return
        netExecutor.execute {
            try {
                val socket = DatagramSocket()
                val raw = "AIRHOP|${target.id}|$command".toByteArray(StandardCharsets.UTF_8)
                val packet = DatagramPacket(raw, raw.size, InetAddress.getByName(target.ip), target.port)
                socket.send(packet)
                socket.close()
                mainHandler.post {
                    telemetryText.text = "TX → ${target.label} : $command"
                }
            } catch (ex: Exception) {
                mainHandler.post {
                    Toast.makeText(this, "LAN error: ${ex.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun ensureBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 303)
        } else {
            startBleScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 303 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBleScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bm.adapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val adv = result.device.name ?: result.scanRecord?.deviceName ?: return
                // Expected beacon name: AIRHOP|ID|192.168.31.105|9876
                if (adv.startsWith("AIRHOP|")) {
                    val parts = adv.split("|")
                    if (parts.size == 4) {
                        val id = parts[1]
                        val ip = parts[2]
                        val port = parts[3].toIntOrNull() ?: 9876
                        val now = System.currentTimeMillis()
                        val node = nodes.getOrPut(id) {
                            HopDevice(id, id, ip, port, result.rssi.toDouble(), now)
                        }
                        node.rssi = (0.35 * result.rssi) + (0.65 * node.rssi)
                        node.lastSeen = now
                        syncSpinner()
                        if (autoProximity) pickNearestNode()
                    }
                }
            }
        })
    }

    private fun pickNearestNode() {
        val now = System.currentTimeMillis()
        val strongest = nodes.values.filter { now - it.lastSeen < 5000 }.maxByOrNull { it.rssi } ?: return
        val current = currentTarget
        if (current == null || (strongest.id != current.id && strongest.rssi > current.rssi + 8.0)) {
            currentTarget = strongest
            refreshTelemetry()
        }
    }

    private fun refreshTelemetry() {
        mainHandler.post {
            val dev = currentTarget ?: return@post
            activeDeviceText.text = "${dev.label} · ${dev.ip}"
            if (autoProximity) {
                telemetryText.text = "AUTO HOP · ${dev.rssi.toInt()} dBm · PORT ${dev.port}"
            }
        }
    }

    private fun syncSpinner() {
        mainHandler.post {
            spinnerItems.clear()
            spinnerIds.clear()
            nodes.values.forEach {
                spinnerItems.add("${it.label} (${it.ip})")
                spinnerIds.add(it.id)
            }
            spinnerAdapter.notifyDataSetChanged()
        }
    }
}
