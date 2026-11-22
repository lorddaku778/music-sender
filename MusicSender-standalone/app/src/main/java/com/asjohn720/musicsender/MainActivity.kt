package com.asjohn720.musicsender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import com.asjohn720.s1mods.discovery.NetworkScanner
import com.asjohn720.s1mods.grpc.S1GrpcClient
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.runBlocking
import okio.source
import java.io.File

private data class Song(val uri: Uri, val rel: String, val size: Long)

/**
 * Copies MP3 (and other audio) files from the phone to the scooter's /sdcard/Music/S1Songs
 * over the same ADB-through-hotspot link S1Mods uses. Already-copied files are skipped,
 * so you can run it again to resume.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var ipBox: EditText
    private lateinit var pickInfo: TextView
    private lateinit var logView: TextView
    private lateinit var bar: ProgressBar
    private lateinit var sendBtn: Button
    private lateinit var scanBtn: Button

    private var scope: Int? = null
    private var songs: List<Song> = emptyList()
    @Volatile private var running = false

    private val audioExt = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma")
    private val remoteBase = "/sdcard/Music/S1Songs"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            setBackgroundColor(0xFF000000.toInt())
        }
        fun label(t: String, size: Float = 14f, color: Int = 0xFFEDEDED.toInt()) =
            TextView(this).apply { text = t; textSize = size; setTextColor(color) }

        col.addView(label("Music Sender", 22f))
        col.addView(label("Copy MP3s from this phone to the scooter. Keep the hotspot on and this screen open.", 12f, 0xFFA1A1A1.toInt()))

        col.addView(label("\n1. Scooter address", 13f, 0xFFA1A1A1.toInt()))
        ipBox = EditText(this).apply {
            hint = "e.g. 192.168.43.50"
            setTextColor(0xFFEDEDED.toInt()); setHintTextColor(0xFF6E6E6E.toInt())
            isSingleLine = true
        }
        col.addView(ipBox)
        scanBtn = button("Auto scan") { autoScan() }
        col.addView(scanBtn)

        col.addView(label("\n2. Choose music", 13f, 0xFFA1A1A1.toInt()))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Pick folder") { pickFolder() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Pick files") { pickFiles() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        pickInfo = label("Nothing selected", 12f, 0xFFA1A1A1.toInt())
        col.addView(pickInfo)

        col.addView(label("\n3. Send", 13f, 0xFFA1A1A1.toInt()))
        sendBtn = button("Send to scooter") { send() }
        col.addView(sendBtn)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        col.addView(bar)
        logView = label("", 11f, 0xFF3FB950.toInt()).apply { typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(logView)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(col)
        })
    }

    private fun log(msg: String) = ui.post {
        val lines = (logView.text.toString() + "\n" + msg).trim().lines().takeLast(14)
        logView.text = lines.joinToString("\n")
    }

    // ------------------------------------------------------------ discovery

    private fun autoScan() {
        scanBtn.isEnabled = false
        log("Scanning for the scooter…")
        Thread {
            try {
                val found = runBlocking { NetworkScanner.scanForScooter(this@MainActivity) }
                ui.post {
                    if (found != null) {
                        scope = found.scope
                        ipBox.setText(found.ip)
                        log("Found ${found.ip}")
                    } else {
                        log("Not found. Is the hotspot on and the scooter connected? You can type the IP instead.")
                    }
                    scanBtn.isEnabled = true
                }
            } catch (e: Exception) {
                log("Scan failed: ${e.message}")
                ui.post { scanBtn.isEnabled = true }
            }
        }.start()
    }

    // ------------------------------------------------------------ picking

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 10)
    }

    private fun pickFiles() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, 11)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        Thread {
            val list = mutableListOf<Song>()
            if (requestCode == 10) {
                val root = data.data?.let { DocumentFile.fromTreeUri(this, it) }
                if (root != null) walk(root, "", list)
            } else if (requestCode == 11) {
                val clip = data.clipData
                val uris = mutableListOf<Uri>()
                if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                else data.data?.let { uris.add(it) }
                for (u in uris) {
                    val f = DocumentFile.fromSingleUri(this, u) ?: continue
                    list.add(Song(u, clean(f.name ?: "song.mp3"), f.length()))
                }
            }
            songs = list
            val mb = list.sumOf { it.size } / (1024.0 * 1024.0)
            ui.post { pickInfo.text = "${list.size} songs, ${"%.0f".format(mb)} MB" }
        }.start()
    }

    private fun walk(dir: DocumentFile, prefix: String, out: MutableList<Song>) {
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (f.isDirectory) {
                walk(f, prefix + clean(name) + "/", out)
            } else {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in audioExt || (f.type ?: "").startsWith("audio/")) {
                    out.add(Song(f.uri, prefix + clean(name), f.length()))
                }
            }
        }
    }

    /** Keeps names safe for the scooter's shell and file system. */
    private fun clean(n: String) = n.replace(Regex("[^A-Za-z0-9._ ()\\-]"), "_")

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------ sending

    private fun send() {
        if (running) return
        val host = ipBox.text.toString().trim()
        if (host.isEmpty()) { log("Enter the scooter address first."); return }
        if (songs.isEmpty()) { log("Pick a folder or files first."); return }
        running = true
        sendBtn.isEnabled = false
        val list = songs
        val total = list.sumOf { it.size }.coerceAtLeast(1L)
        Thread {
            var done = 0L
            var copied = 0
            var skipped = 0
            var failed = 0
            try {
                log("Authorising ADB over the daemon…")
                val dir = File(filesDir, "adb").apply { mkdirs() }
                val priv = File(dir, "adbkey")
                val pub = File(dir, "adbkey.pub")
                if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
                val keyPair = AdbKeyPair.read(priv, pub)
                val pubText = pub.readText().trim()

                val grpc = S1GrpcClient(host = host, port = 50005, scope = scope)
                runBlocking {
                    grpc.connect(6L)
                    grpc.unlockAdb(pubText)
                }
                grpc.close()

                log("Connecting ADB…")
                val clean = NetworkScanner.normalizeHost(host)
                Dadb.create(clean, 5555, keyPair).use { d ->
                    d.shell("mkdir -p ${q(remoteBase)}")
                    val made = HashSet<String>()
                    for ((i, s) in list.withIndex()) {
                        val remote = "$remoteBase/${s.rel}"
                        val parent = remote.substringBeforeLast('/')
                        if (made.add(parent)) d.shell("mkdir -p ${q(parent)}")
                        try {
                            val have = d.shell("stat -c %s ${q(remote)} 2>/dev/null")
                                .allOutput.trim().toLongOrNull()
                            if (have != null && have == s.size) {
                                skipped++
                            } else {
                                contentResolver.openInputStream(s.uri)!!.use { ins ->
                                    d.push(ins.source(), remote, 420, System.currentTimeMillis())
                                }
                                copied++
                            }
                        } catch (e: Exception) {
                            failed++
                            log("Failed: ${s.rel} (${e.message})")
                        }
                        done += s.size
                        val frac = (done * 1000 / total).toInt()
                        ui.post { bar.progress = frac }
                        log("${i + 1}/${list.size}  ${s.rel.substringAfterLast('/')}")
                    }
                }
                log("Done. Copied $copied, already there $skipped, failed $failed.")
                log("Files are in $remoteBase on the scooter.")
            } catch (e: Exception) {
                log("Stopped: ${e.message ?: e.toString()}")
                log("Run it again to continue; copied files are skipped.")
            } finally {
                running = false
                ui.post { sendBtn.isEnabled = true }
            }
        }.start()
    }
}
