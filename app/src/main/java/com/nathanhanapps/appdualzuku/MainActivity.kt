package com.nathanhanapps.appdualzuku

import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.nathanhanapps.appdualzuku.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import com.google.android.material.color.MaterialColors

class MainActivity : AppCompatActivity() {

    private val bg = Executors.newSingleThreadExecutor()

    private lateinit var repo: AppRepository
    private lateinit var adapter: AppAdapter
    private lateinit var shell: ShellClient
    private lateinit var binding: ActivityMainBinding

    private var cachedFullList: List<AppItem> = emptyList()
    private val REQUEST_SHIZUKU_PERMISSION = 1234

    private var isInitialized = false
    private var currentFilter = AppRepository.AppFilter.ALL

    private val uiHandler = Handler(Looper.getMainLooper())
    private var showShimmerRunnable: Runnable? = null
    private val SHIMMER_DELAY_MS = 400L  // tweak 300~500

    private fun applyStatusBarToMatchToolbar() {
        // Use the same surface color as the toolbar
        val surfaceColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurface,
            0
        )

        window.statusBarColor = surfaceColor

        // Decide icon color automatically (black icons on light bg, white on dark bg)
        val lightBackground = MaterialColors.isColorLight(surfaceColor)
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = lightBackground
    }
    private fun scheduleShowShimmer() {
        cancelShowShimmer()

        // Don’t show immediately; avoid flicker
        showShimmerRunnable = Runnable {
            binding.shimmerOverlay.visibility = View.VISIBLE
            binding.shimmerOverlay.startShimmer()
        }
        uiHandler.postDelayed(showShimmerRunnable!!, SHIMMER_DELAY_MS)
    }

    private fun hideShimmerNow() {
        cancelShowShimmer()
        binding.shimmerOverlay.stopShimmer()
        binding.shimmerOverlay.visibility = View.GONE
    }

    private fun cancelShowShimmer() {
        showShimmerRunnable?.let { uiHandler.removeCallbacks(it) }
        showShimmerRunnable = null
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_SHIZUKU_PERMISSION) {
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Shizuku permission granted", Toast.LENGTH_SHORT).show()
                    initializeShellAndUpdate()
                } else {
                    Toast.makeText(this, "Shizuku permission required", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getLauncherComponent(packageName: String): String? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        val component = intent.component ?: return null
        return component.flattenToShortString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarToMatchToolbar()

        setupUI()
        repo = AppRepository(this)
        loadAppsUser0()
        checkShizukuAndInitialize()
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter { item -> showAppActionsBottomSheet(item) }
        binding.rvApps.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Setup Bottom Navigation
        binding.bottomNav.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_all -> {
                    showAppList()
                    currentFilter = AppRepository.AppFilter.ALL
                    loadAppsUser0()
                    true
                }
                R.id.nav_user -> {
                    showAppList()
                    currentFilter = AppRepository.AppFilter.USER_ONLY
                    loadAppsUser0()
                    true
                }
                R.id.nav_system -> {
                    showAppList()
                    currentFilter = AppRepository.AppFilter.SYSTEM_ONLY
                    loadAppsUser0()
                    true
                }
                R.id.nav_settings -> {
                    showSettings()
                    true
                }
                else -> false
            }
        }
    }

    private fun showAppList() {
        findViewById<LinearLayout>(R.id.layoutAppList).visibility = View.VISIBLE
        findViewById<View>(R.id.layoutSettings).visibility = View.GONE
    }

    private fun showSettings() {
        findViewById<LinearLayout>(R.id.layoutAppList).visibility = View.GONE
        findViewById<View>(R.id.layoutSettings).visibility = View.VISIBLE
        title = "Settings"
    }

    private fun checkShizukuAndInitialize() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku not running... Please check.", Toast.LENGTH_LONG).show()
            return
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {

            Shizuku.requestPermission(REQUEST_SHIZUKU_PERMISSION)
        } else {

            initializeShellAndUpdate()
        }
    }

    private fun initializeShellAndUpdate() {
        if (isInitialized) {
//            Log.d("MainActivity", "Already initialized, skipping")
            return
        }

        try {
            shell = ShellClient(this)
            isInitialized = true
            updateDualStatusIfPossible()
        } catch (e: Exception) {
            Toast.makeText(this, "Error initializing: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadAppsUser0() {
        runOnUiThread { scheduleShowShimmer() }

        bg.execute {
            try {
                val list = repo.loadInstalledAppsUser0(currentFilter)
                cachedFullList = list

                runOnUiThread {
                    // Hide shimmer as soon as real data is ready
                    hideShimmerNow()

                    adapter.submitFullList(list)
                    updateTitle(list)

                    // keep your existing behavior (don’t change icon loading)
                    updateDualStatusIfPossible()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideShimmerNow()
                    Toast.makeText(this, "Error loading apps: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }



    private fun updateDualStatusIfPossible() {
        if (!::shell.isInitialized) {
//            Log.d("MainActivity", "Shell not initialized yet")
            return
        }

        if (!Shizuku.pingBinder()) {
//            Log.d("MainActivity", "Shizuku binder not ready")
            Toast.makeText(this, "Shizuku not running", Toast.LENGTH_SHORT).show()
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
//            Log.d("MainActivity", "Shizuku permission not granted")
            return
        }

//        Log.d("MainActivity", "Requesting dual app list...")
        val cmd = "pm list packages --user 999"

        shell.execWhenReady(cmd) { output ->
//            Log.d("MainActivity", "pm command output: $output")

            runOnUiThread {
                if (output.startsWith("ERROR:")) {
                    Toast.makeText(this, output, Toast.LENGTH_LONG).show()
                } else {
                    val dualPackages = PmParsers.parsePmListPackages(output)
//                    Log.d("MainActivity", "Found ${dualPackages.size} dual apps")
                    updateDualAppsInList(dualPackages)
                }
            }
        }
    }

    private fun updateDualAppsInList(dualPackages: Set<String>) {
        bg.execute {
            val updatedList = cachedFullList.map { item ->
                item.copy(isDual = dualPackages.contains(item.packageName))
            }

            cachedFullList = updatedList

            runOnUiThread {
                adapter.submitFullList(updatedList)
                updateTitle(updatedList)
            }
        }
    }

    private fun updateTitle(list: List<AppItem>) {
        val dualCount = list.count { it.isDual }
        val filterText = when (currentFilter) {
            AppRepository.AppFilter.ALL -> "All"
            AppRepository.AppFilter.USER_ONLY -> "User"
            AppRepository.AppFilter.SYSTEM_ONLY -> "System"
            else -> "All"
        }
        title = "AppDualZuku ($filterText: ${list.size})  Dual: $dualCount"
    }
    private fun requireShizukuOrToast(): Boolean {
        val ok = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && ::shell.isInitialized
        if (!ok) Toast.makeText(this, "Shizuku permission required", Toast.LENGTH_SHORT).show()
        return ok
    }

    private fun openAppInfo(userId: Int, packageName: String) {
        if (!requireShizukuOrToast()) return

        // Settings App info page
        val cmd = "am start --user $userId -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$packageName"
        shell.execWhenReady(cmd) { output ->
            runOnUiThread {
                if (output.startsWith("ERROR:") || output.contains("Error", ignoreCase = true)) {
                    Toast.makeText(this, "Failed to open App info: $output", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun resolveLauncherComponent(userId: Int, packageName: String, callback: (String?) -> Unit) {
        if (!requireShizukuOrToast()) { callback(null); return }

        // cmd package resolve-activity gives us the launcher activity component reliably
        // Note: output varies by Android version/OEM; we parse for "name=" or "cmp="
        val cmd = "cmd package resolve-activity --brief --user $userId -c android.intent.category.LAUNCHER $packageName"
        shell.execWhenReady(cmd) { out ->
            val output = out.trim()

            // Typical outputs:
            // - "com.pkg/.MainActivity"
            // - "name=com.pkg/.MainActivity"
            // - "cmp=com.pkg/.MainActivity"
            val component = when {
                output.contains("/") && !output.contains(" ") -> output
                output.contains("cmp=") -> output.substringAfter("cmp=").trim().lineSequence().firstOrNull()
                output.contains("name=") -> output.substringAfter("name=").trim().lineSequence().firstOrNull()
                else -> null
            }

            runOnUiThread { callback(component) }
        }
    }

    private fun launchApp(userId: Int, packageName: String) {
        if (!requireShizukuOrToast()) return

        val component = getLauncherComponent(packageName)
        if (component == null) {
            Toast.makeText(this, "No launcher activity found", Toast.LENGTH_SHORT).show()
            return
        }

        val cmd = "am start --user $userId -n $component"
        shell.execWhenReady(cmd) { output ->
            runOnUiThread {
                if (output.contains("Error", ignoreCase = true)) {
                    Toast.makeText(this, "Launch failed: $output", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    // ========== BOTTOM SHEET & ACTIONS ==========

    private fun showAppActionsBottomSheet(item: AppItem) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_app_actions, null)
        dialog.setContentView(view)

        // Setup header
        view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(item.icon)
        view.findViewById<TextView>(R.id.tvAppName).text = item.label
        view.findViewById<TextView>(R.id.tvPackageName).text = item.packageName

        val btnInstallDual = view.findViewById<MaterialButton>(R.id.btnInstallDual)
        val btnUninstallDual = view.findViewById<MaterialButton>(R.id.btnUninstallDual)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val layoutProgress = view.findViewById<LinearLayout>(R.id.layoutProgress)
        val tvProgressText = view.findViewById<TextView>(R.id.tvProgressText)
        val btnAppInfoMain = view.findViewById<MaterialButton>(R.id.btnAppInfoMain)
        val btnAppInfoDual = view.findViewById<MaterialButton>(R.id.btnAppInfoDual)
        val btnLaunchMain = view.findViewById<MaterialButton>(R.id.btnLaunchMain)
        val btnLaunchDual = view.findViewById<MaterialButton>(R.id.btnLaunchDual)

        val shizukuReady = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        btnAppInfoMain.isEnabled = shizukuReady
        btnAppInfoDual.isEnabled = shizukuReady
        btnLaunchMain.isEnabled = shizukuReady
        btnLaunchDual.isEnabled = shizukuReady

        // Dual-only buttons visible only if app is dual-installed
        btnAppInfoDual.visibility = if (item.isDual) View.VISIBLE else View.GONE
        btnLaunchDual.visibility = if (item.isDual) View.VISIBLE else View.GONE


        // Show/hide buttons based on dual status
        if (item.isDual) {
            btnInstallDual.visibility = View.GONE
            btnUninstallDual.visibility = View.VISIBLE
        } else {
            btnInstallDual.visibility = View.VISIBLE
            btnUninstallDual.visibility = View.GONE
        }

        // Install to dual space
        btnInstallDual.setOnClickListener {
            setBottomSheetLoading(view, layoutProgress, tvProgressText, "Installing...", true)
            installToDualSpace(item.packageName) { success, message ->
                if (success) {
                    Toast.makeText(this, "Installed to Dual Space", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    updateDualStatusIfPossible() // Refresh list
                } else {
                    Toast.makeText(this, "Failed: $message", Toast.LENGTH_LONG).show()
                    setBottomSheetLoading(view, layoutProgress, tvProgressText, "", false)
                }
            }
        }

        // Uninstall from dual space
        btnUninstallDual.setOnClickListener {
            setBottomSheetLoading(view, layoutProgress, tvProgressText, "Removing...", true)
            uninstallFromDualSpace(item.packageName) { success, message ->
                if (success) {
                    Toast.makeText(this, "Removed from Dual Space", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    updateDualStatusIfPossible() // Refresh list
                } else {
                    Toast.makeText(this, "Failed: $message", Toast.LENGTH_LONG).show()
                    setBottomSheetLoading(view, layoutProgress, tvProgressText, "", false)
                }
            }
        }

        btnAppInfoMain.setOnClickListener {
            openAppInfo(userId = 0, packageName = item.packageName)
        }

        btnAppInfoDual.setOnClickListener {
            if (item.isDual) openAppInfo(userId = 999, packageName = item.packageName)
        }

        btnLaunchMain.setOnClickListener {
            launchApp(userId = 0, packageName = item.packageName)
        }

        btnLaunchDual.setOnClickListener {
            if (item.isDual) launchApp(userId = 999, packageName = item.packageName)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setBottomSheetLoading(
        view: View,
        layoutProgress: LinearLayout,
        tvProgressText: TextView,
        progressText: String,
        isLoading: Boolean
    ) {
        view.findViewById<MaterialButton>(R.id.btnInstallDual).isEnabled = !isLoading
        view.findViewById<MaterialButton>(R.id.btnUninstallDual).isEnabled = !isLoading
        view.findViewById<MaterialButton>(R.id.btnCancel).isEnabled = !isLoading

        if (isLoading) {
            layoutProgress.visibility = View.VISIBLE
            tvProgressText.text = progressText
        } else {
            layoutProgress.visibility = View.GONE
        }
    }

    /**
     * Install an existing package to user 999 (dual space)
     */
    private fun installToDualSpace(packageName: String, callback: (Boolean, String) -> Unit) {
        if (!::shell.isInitialized) {
            runOnUiThread { callback(false, "Shell not initialized") }
            return
        }

        val cmd = "pm install-existing --user 999 $packageName"
//        Log.d("MainActivity", "Executing: $cmd")

        shell.execWhenReady(cmd) { output ->
//            Log.d("MainActivity", "Install output: $output")

            val success = output.contains("Package", ignoreCase = true) &&
                    output.contains("installed", ignoreCase = true)

            runOnUiThread {
                if (success) {
                    callback(true, "Success")
                } else {
                    callback(false, output)
                }
            }
        }
    }

    /**
     * Uninstall package from user 999 (dual space)
     */
    private fun uninstallFromDualSpace(packageName: String, callback: (Boolean, String) -> Unit) {
        if (!::shell.isInitialized) {
            runOnUiThread { callback(false, "Shell not initialized") }
            return
        }

        val cmd = "pm uninstall --user 999 $packageName"

        shell.execWhenReady(cmd) { output ->

            val success = output.contains("Success", ignoreCase = true)

            runOnUiThread {
                if (success) {
                    callback(true, "Success")
                } else {
                    callback(false, output)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (::shell.isInitialized) {
            runCatching { shell.unbind() }
        }
        bg.shutdown()
    }
}