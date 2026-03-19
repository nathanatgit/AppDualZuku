package com.nathanhanapps.appdualzuku

import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.nathanhanapps.appdualzuku.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper

class MainActivity : AppCompatActivity() {

    // ── Background executor ──────────────────────────────────────────────────
    private val bg = Executors.newSingleThreadExecutor()

    // ── Core components ──────────────────────────────────────────────────────
    private lateinit var repo:      AppRepository
    private lateinit var adapter:   AppAdapter
    private lateinit var shell:     ShellClient
    private lateinit var wsRepo:    WorkspaceRepository
    private lateinit var wsAdapter: WorkspaceAdapter
    private lateinit var binding:   ActivityMainBinding

    // ── State caches ─────────────────────────────────────────────────────────
    private var cachedFullList:   List<AppItem>       = emptyList()
    private var cachedWorkspaces: List<WorkspaceInfo> = emptyList()

    // ── UI / lifecycle flags ──────────────────────────────────────────────────
    private val REQUEST_SHIZUKU_PERMISSION = 1234
    private var isInitialized = false
    private var aboutClickCount = 0
    private var currentFilter = AppRepository.AppFilter.ALL

    // ── Shimmer debounce ─────────────────────────────────────────────────────
    private val uiHandler = Handler(Looper.getMainLooper())
    private var showShimmerRunnable: Runnable? = null
    private val SHIMMER_DELAY_MS = 400L

    // ════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════════════════

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


    // ════════════════════════════════════════════════════════════════════════
    //  Hidden Developer Console
    // ════════════════════════════════════════════════════════════════════════

    private fun setupDevPanel() {
        val cardAbout   = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardAbout)
        val cardDevPanel = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardDevPanel)
        val etDevCmd    = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDevCmd)
        val btnDevRun   = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDevRun)
        val btnDevClear = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDevClear)
        val tvDevOutput = findViewById<TextView>(R.id.tvDevOutput)
        val scrollDevOutput = findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollDevOutput)

        // 5-tap unlock on the About card
        cardAbout.setOnClickListener {
            aboutClickCount++
            val remaining = 5 - aboutClickCount
            when {
                aboutClickCount in 1..4 -> {
                    Toast.makeText(this, "$remaining more tap${if (remaining > 1) "s" else ""} to unlock dev console", Toast.LENGTH_SHORT).show()
                }
                aboutClickCount >= 5 -> {
                    aboutClickCount = 0
                    val isVisible = cardDevPanel.visibility == View.VISIBLE
                    cardDevPanel.visibility = if (isVisible) View.GONE else View.VISIBLE
                    if (!isVisible) {
                        Toast.makeText(this, "🛠 Developer console unlocked", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Run button
        btnDevRun.setOnClickListener {
            val cmd = etDevCmd.text?.toString()?.trim() ?: return@setOnClickListener
            if (cmd.isEmpty()) return@setOnClickListener
            if (!::shell.isInitialized) {
                tvDevOutput.text = "ERROR: Shizuku shell not initialized"
                return@setOnClickListener
            }

            btnDevRun.isEnabled = false
            tvDevOutput.text = "Running: $cmd  ..."

            shell.execWhenReady(cmd) { output ->
                runOnUiThread {
                    btnDevRun.isEnabled = true
                    val result = buildString {
                        append("$ ").append(cmd).append("")
                                    append("─".repeat(40)).append("")
                                        append(output.ifBlank { "(no output)" })
                    }
                    tvDevOutput.text = result
                    // Scroll to top so user sees the command echo
                    scrollDevOutput.post { scrollDevOutput.scrollTo(0, 0) }
                }
            }
        }

        // Allow Enter key to run command
        etDevCmd.setOnEditorActionListener { _, _, _ ->
            btnDevRun.performClick()
            true
        }

        // Clear button
        btnDevClear.setOnClickListener {
            tvDevOutput.setText(R.string.dev_output_hint)
            etDevCmd.text?.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (::shell.isInitialized) runCatching { shell.unbind() }
        bg.shutdown()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UI Setup
    // ════════════════════════════════════════════════════════════════════════

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)

        // ── App list RecyclerView ────────────────────────────────────────────
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

        // ── Dual filter chip ─────────────────────────────────────────────────
        val chipDualFilter = binding.chipDualFilter
        val filterStates = listOf(
            AppAdapter.DualFilter.ALL       to "All",
            AppAdapter.DualFilter.DUAL_ONLY to "In workspace",
            AppAdapter.DualFilter.MAIN_ONLY to "Main only"
        )
        var filterIndex = 0
        chipDualFilter.setOnClickListener {
            filterIndex = (filterIndex + 1) % filterStates.size
            val (filter, label) = filterStates[filterIndex]
            chipDualFilter.text    = label
            chipDualFilter.isChecked = filterIndex != 0
            adapter.setDualFilter(filter)
        }

        // ── Bottom navigation ────────────────────────────────────────────────
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

        // ── About card secret tap ───────────────────────────────────────────
        setupDevPanel()

        // ── Workspace adapter for Settings ───────────────────────────────────
        wsAdapter = WorkspaceAdapter(
            onStart  = { ws -> doStartWorkspace(ws) },
            onStop   = { ws -> doStopWorkspace(ws) },
            onRemove = { ws -> confirmRemoveWorkspace(ws) }
        )
        val rvWorkspaces = findViewById<RecyclerView>(R.id.rvWorkspaces)
        rvWorkspaces.layoutManager = LinearLayoutManager(this)
        rvWorkspaces.adapter       = wsAdapter
        rvWorkspaces.isNestedScrollingEnabled = false

        // ── Create workspace button ──────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnCreateWorkspace).setOnClickListener {
            if (!requireShizukuOrToast()) return@setOnClickListener
            val name = wsRepo.suggestName(cachedWorkspaces)
            doCreateWorkspace(name)
        }

        // ── Refresh workspaces button ────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btnRefreshWorkspaces).setOnClickListener {
            if (::wsRepo.isInitialized) loadWorkspaces()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  View Switching
    // ════════════════════════════════════════════════════════════════════════

    private fun showAppList() {
        binding.layoutAppList.visibility = View.VISIBLE
        binding.layoutSettings.visibility = View.GONE
    }

    private fun showSettings() {
        binding.layoutAppList.visibility = View.GONE
        binding.layoutSettings.visibility = View.VISIBLE
        title = getString(R.string.settings)
        if (::wsRepo.isInitialized) loadWorkspaces()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Shimmer
    // ════════════════════════════════════════════════════════════════════════

    private fun scheduleShowShimmer() {
        cancelShowShimmer()
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

    // ════════════════════════════════════════════════════════════════════════
    //  Status bar
    // ════════════════════════════════════════════════════════════════════════

    private fun applyStatusBarToMatchToolbar() {
        val surfaceColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurface, 0
        )
        @Suppress("DEPRECATION")
        window.statusBarColor = surfaceColor
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = MaterialColors.isColorLight(surfaceColor)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Shizuku initialisation
    // ════════════════════════════════════════════════════════════════════════

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_SHIZUKU_PERMISSION) {
            runOnUiThread {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Shizuku permission granted", Toast.LENGTH_SHORT).show()
                    initializeShellAndUpdate()
                } else {
                    Toast.makeText(this, "Shizuku permission required", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkShizukuAndInitialize() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, getString(R.string.shi_not_run), Toast.LENGTH_LONG).show()
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
        if (isInitialized) return
        try {
            shell    = ShellClient(this)
            wsRepo   = WorkspaceRepository(shell)
            isInitialized = true
            updateAllWorkspaceStatuses()
        } catch (e: Exception) {
            Toast.makeText(this, "Error initializing: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requireShizukuOrToast(): Boolean {
        val ok = Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
                ::shell.isInitialized
        if (!ok) Toast.makeText(this, getString(R.string.shizuku_required), Toast.LENGTH_SHORT).show()
        return ok
    }

    // ════════════════════════════════════════════════════════════════════════
    //  App list loading
    // ════════════════════════════════════════════════════════════════════════

    private fun loadAppsUser0() {
        runOnUiThread { scheduleShowShimmer() }
        bg.execute {
            try {
                val list = repo.loadInstalledAppsUser0(currentFilter)
                cachedFullList = list
                runOnUiThread {
                    hideShimmerNow()
                    adapter.submitFullList(list)
                    updateTitle(list)
                    updateAllWorkspaceStatuses()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideShimmerNow()
                    Toast.makeText(this, "Error loading apps: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateTitle(list: List<AppItem>) {
        val filterText = when (currentFilter) {
            AppRepository.AppFilter.ALL         -> "All"
            AppRepository.AppFilter.USER_ONLY   -> "User"
            AppRepository.AppFilter.SYSTEM_ONLY -> "System"
        }
        val inWorkspace = list.count { it.isDual }
        title = "AppDualZuku ($filterText: ${list.size})  ·  Workspaces: $inWorkspace"
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Workspace status polling (updates isDual / installedUserIds for each app)
    // ════════════════════════════════════════════════════════════════════════

    private fun updateAllWorkspaceStatuses() {
        if (!::wsRepo.isInitialized) return
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return

        wsRepo.listWorkspaces { workspaces ->
            cachedWorkspaces = workspaces
            val managed = workspaces.filter { !it.isMainUser }

            if (managed.isEmpty()) {
                runOnUiThread { applyWorkspaceStatus(emptyMap()) }
                return@listWorkspaces
            }

            // Sequentially query each workspace to avoid overloading the shell client
            loadPackagesSequentially(managed, 0, mutableMapOf())
        }
    }

    private fun loadPackagesSequentially(
        workspaces: List<WorkspaceInfo>,
        index: Int,
        acc: MutableMap<Int, Set<String>>
    ) {
        if (index >= workspaces.size) {
            val snapshot = acc.toMap()
            runOnUiThread { applyWorkspaceStatus(snapshot) }
            return
        }
        val ws = workspaces[index]
        wsRepo.getInstalledPackages(ws.userId) { pkgs ->
            acc[ws.userId] = pkgs
            loadPackagesSequentially(workspaces, index + 1, acc)
        }
    }

    private fun applyWorkspaceStatus(installedByUser: Map<Int, Set<String>>) {
        bg.execute {
            val updated = cachedFullList.map { item ->
                val userIds = installedByUser.entries
                    .filter { (_, pkgs) -> pkgs.contains(item.packageName) }
                    .map { it.key }
                    .toSet()
                item.copy(installedUserIds = userIds)
            }
            cachedFullList = updated
            runOnUiThread {
                adapter.submitFullList(updated)
                updateTitle(updated)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Workspace settings management
    // ════════════════════════════════════════════════════════════════════════

    private fun loadWorkspaces() {
        wsRepo.listWorkspaces { workspaces ->
            cachedWorkspaces = workspaces
            val managed = workspaces.filter { !it.isMainUser }
            runOnUiThread {
                wsAdapter.submitList(managed)
                val tvEmpty = findViewById<TextView>(R.id.tvNoWorkspacesInfo)
                tvEmpty.visibility = if (managed.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun doCreateWorkspace(name: String) {
        val btnCreate = findViewById<MaterialButton>(R.id.btnCreateWorkspace)
        btnCreate.isEnabled = false
        Toast.makeText(this, "Creating workspace \"$name\"…", Toast.LENGTH_SHORT).show()

        wsRepo.createWorkspace(name) { success, userId, output ->
            runOnUiThread { btnCreate.isEnabled = true }
            if (success) {
                // Auto-start new workspace so it's immediately usable
                wsRepo.startWorkspace(userId) { _, _ ->
                    runOnUiThread {
                        Toast.makeText(this, "Workspace \"$name\" created (User $userId)", Toast.LENGTH_SHORT).show()
                        loadWorkspaces()
                        updateAllWorkspaceStatuses()
                    }
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Failed to create workspace: $output", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmRemoveWorkspace(ws: WorkspaceInfo) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.remove_workspace_title))
            .setMessage(getString(R.string.remove_workspace_confirm, ws.displayName, ws.userId))
            .setPositiveButton(getString(R.string.remove)) { _, _ -> doRemoveWorkspace(ws) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        // Tint the destructive button red after show() so the button exists in the view tree
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
        }
    }

    private fun doRemoveWorkspace(ws: WorkspaceInfo) {
        Toast.makeText(this, "Removing workspace \"${ws.displayName}\"…", Toast.LENGTH_SHORT).show()
        wsRepo.removeWorkspace(ws.userId) { success, output ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "Workspace removed", Toast.LENGTH_SHORT).show()
                    loadWorkspaces()
                    updateAllWorkspaceStatuses()
                } else {
                    Toast.makeText(this, "Failed: $output", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun doStartWorkspace(ws: WorkspaceInfo) {
        Toast.makeText(this, "Starting \"${ws.displayName}\"…", Toast.LENGTH_SHORT).show()
        wsRepo.startWorkspace(ws.userId) { success, output ->
            runOnUiThread {
                if (success) {
                    loadWorkspaces()
                } else {
                    Toast.makeText(this, "Failed: $output", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun doStopWorkspace(ws: WorkspaceInfo) {
        Toast.makeText(this, "Stopping \"${ws.displayName}\"…", Toast.LENGTH_SHORT).show()
        wsRepo.stopWorkspace(ws.userId) { success, output ->
            runOnUiThread {
                if (success) {
                    loadWorkspaces()
                } else {
                    Toast.makeText(this, "Failed: $output", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  App launch / info helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun getLauncherComponent(packageName: String): String? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return intent.component?.flattenToShortString()
    }

    private fun launchApp(userId: Int, packageName: String) {
        if (!requireShizukuOrToast()) return
        val component = getLauncherComponent(packageName)
        if (component == null) {
            Toast.makeText(this, "No launcher activity found", Toast.LENGTH_SHORT).show()
            return
        }
        wsRepo.launchInWorkspace(userId, component) { success, output ->
            runOnUiThread {
                if (!success) Toast.makeText(this, "Launch failed: $output", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openAppInfo(userId: Int, packageName: String) {
        if (!requireShizukuOrToast()) return
        wsRepo.openAppInfoInWorkspace(userId, packageName) { success, output ->
            runOnUiThread {
                if (!success) Toast.makeText(this, "Failed: $output", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  App-actions bottom sheet
    // ════════════════════════════════════════════════════════════════════════

    private fun showAppActionsBottomSheet(item: AppItem) {
        val dialog = BottomSheetDialog(this)
        val view   = layoutInflater.inflate(R.layout.bottom_sheet_app_actions, null)
        dialog.setContentView(view)

        // Header
        view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(item.icon)
        view.findViewById<TextView>(R.id.tvAppName).text     = item.label
        view.findViewById<TextView>(R.id.tvPackageName).text = item.packageName

        val shizukuReady = Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

        // Main-user buttons
        val btnLaunchMain  = view.findViewById<MaterialButton>(R.id.btnLaunchMain)
        val btnAppInfoMain = view.findViewById<MaterialButton>(R.id.btnAppInfoMain)
        btnLaunchMain.isEnabled  = shizukuReady
        btnAppInfoMain.isEnabled = shizukuReady
        btnLaunchMain.setOnClickListener  { launchApp(0, item.packageName) }
        btnAppInfoMain.setOnClickListener { openAppInfo(0, item.packageName) }
        view.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }

        // Workspace rows
        val wsContainer   = view.findViewById<LinearLayout>(R.id.layoutWorkspaceActions)
        val tvNoWorkspaces = view.findViewById<TextView>(R.id.tvNoWorkspaces)
        val layoutProgress = view.findViewById<LinearLayout>(R.id.layoutProgress)
        val tvProgressText = view.findViewById<TextView>(R.id.tvProgressText)

        val managedWorkspaces = cachedWorkspaces.filter { !it.isMainUser }
        if (managedWorkspaces.isEmpty()) {
            tvNoWorkspaces.visibility = View.VISIBLE
        } else {
            tvNoWorkspaces.visibility = View.GONE
            managedWorkspaces.forEach { ws ->
                val row = layoutInflater.inflate(R.layout.item_workspace_action, wsContainer, false)
                bindWorkspaceActionRow(row, ws, item, dialog, layoutProgress, tvProgressText)
                wsContainer.addView(row)
            }
        }

        dialog.show()
    }

    /**
     * Configures a single workspace row inside the app-actions bottom sheet.
     * Handles install ↔ uninstall toggle and launch / app-info buttons.
     */
    private fun bindWorkspaceActionRow(
        row: View,
        ws: WorkspaceInfo,
        item: AppItem,
        dialog: BottomSheetDialog,
        layoutProgress: LinearLayout,
        tvProgressText: TextView
    ) {
        var isInstalled = item.installedUserIds.contains(ws.userId)

        val tvName          = row.findViewById<TextView>(R.id.tvWsActionName)
        val chipInstalled   = row.findViewById<Chip>(R.id.chipWsInstalled)
        val btnInstall      = row.findViewById<MaterialButton>(R.id.btnWsInstallToggle)
        val btnLaunch       = row.findViewById<MaterialButton>(R.id.btnWsLaunch)
        val btnInfo         = row.findViewById<MaterialButton>(R.id.btnWsAppInfo)

        val running = ws.isRunning
        tvName.text = buildString {
            append(ws.displayName)
            append("  (User ${ws.userId}")
            if (!running) append(" · Stopped")
            append(")")
        }

        fun refreshRow(installed: Boolean) {
            isInstalled              = installed
            chipInstalled.text       = if (installed) getString(R.string.installed) else getString(R.string.not_installed)
            chipInstalled.isChecked  = installed
            btnInstall.text          = if (installed) getString(R.string.uninstall) else getString(R.string.install)
            btnLaunch.isEnabled      = installed && running
            btnInfo.isEnabled        = installed
        }

        refreshRow(isInstalled)

        btnInstall.setOnClickListener {
            val actionText = if (isInstalled) "Removing…" else "Installing…"
            layoutProgress.visibility = View.VISIBLE
            tvProgressText.text       = actionText
            btnInstall.isEnabled      = false
            btnLaunch.isEnabled       = false

            if (isInstalled) {
                wsRepo.uninstallFromWorkspace(ws.userId, item.packageName) { ok, msg ->
                    runOnUiThread {
                        layoutProgress.visibility = View.GONE
                        btnInstall.isEnabled      = true
                        if (ok) {
                            refreshRow(false)
                            updateAllWorkspaceStatuses()
                        } else {
                            Toast.makeText(this, "Failed: $msg", Toast.LENGTH_LONG).show()
                            refreshRow(isInstalled)
                        }
                    }
                }
            } else {
                // Auto-start workspace if not running before installing
                val doInstall = {
                    wsRepo.installToWorkspace(ws.userId, item.packageName) { ok, msg ->
                        runOnUiThread {
                            layoutProgress.visibility = View.GONE
                            btnInstall.isEnabled      = true
                            if (ok) {
                                refreshRow(true)
                                updateAllWorkspaceStatuses()
                            } else {
                                Toast.makeText(this, "Failed: $msg", Toast.LENGTH_LONG).show()
                                btnInstall.isEnabled = true
                            }
                        }
                    }
                }
                if (!ws.isRunning) {
                    wsRepo.startWorkspace(ws.userId) { _, _ -> doInstall() }
                } else {
                    doInstall()
                }
            }
        }

        btnLaunch.setOnClickListener {
            dialog.dismiss()
            launchApp(ws.userId, item.packageName)
        }

        btnInfo.setOnClickListener {
            openAppInfo(ws.userId, item.packageName)
        }
    }
}