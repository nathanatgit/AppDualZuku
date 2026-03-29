package com.nathanhanapps.appdualzuku

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nathanhanapps.appdualzuku.databinding.ActivityMainBinding
import com.nathanhanapps.appdualzuku.databinding.BottomSheetAppActionsBinding
import com.nathanhanapps.appdualzuku.databinding.ItemWorkspaceActionBinding
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import androidx.core.view.isVisible

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
    private var isInitialized = false
    private var aboutClickCount = 0
    private var currentFilter = AppRepository.AppFilter.ALL

    // ── Shimmer debounce ─────────────────────────────────────────────────────
    private val uiHandler = Handler(Looper.getMainLooper())
    private var showShimmerRunnable: Runnable? = null

    companion object {
        private const val REQUEST_SHIZUKU_PERMISSION = 1234
        private const val SHIMMER_DELAY_MS = 400L
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply Material 3 Dynamic Colors
        DynamicColors.applyToActivityIfAvailable(this)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarToMatchToolbar()
        setupUI()
        setupAboutVersion()

        repo = AppRepository(this)
        loadAppsUser0()
        checkShizukuAndInitialize()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView

        searchView.queryHint = getString(R.string.search_apps)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter(newText.orEmpty())
                return true
            }
        })

        // Hide search when switched to Settings
        searchItem.isVisible = binding.layoutAppList.isVisible

        return true
    }


    // ════════════════════════════════════════════════════════════════════════
    //  Hidden Developer Console
    // ════════════════════════════════════════════════════════════════════════

    private fun setupDevPanel() {
        val cardAbout   = binding.cardAbout
        val cardDevPanel = binding.cardDevPanel
        val etDevCmd    = binding.etDevCmd
        val btnDevRun   = binding.btnDevRun
        val btnDevClear = binding.btnDevClear
        val tvDevOutput = binding.tvDevOutput
        val scrollDevOutput = binding.scrollDevOutput

        // 5-tap unlock on the About card
        cardAbout.setOnClickListener {
            aboutClickCount++
            val remaining = 5 - aboutClickCount
            when {
                aboutClickCount in 1..4 -> {
                    Toast.makeText(this, getString(R.string.dev_remaining_taps, remaining, if (remaining > 1) "s" else ""), Toast.LENGTH_SHORT).show()
                }
                aboutClickCount >= 5 -> {
                    aboutClickCount = 0
                    val isCurrentlyVisible = cardDevPanel.isVisible
                    cardDevPanel.isVisible = !isCurrentlyVisible
                    if (!isCurrentlyVisible) {
                        Toast.makeText(this, getString(R.string.dev_console_unlocked), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Run button
        btnDevRun.setOnClickListener {
            val cmd = etDevCmd.text?.toString()?.trim() ?: return@setOnClickListener
            if (cmd.isEmpty()) return@setOnClickListener
            if (!::shell.isInitialized) {
                tvDevOutput.text = getString(R.string.error_shizuku_not_init)
                return@setOnClickListener
            }

            btnDevRun.isEnabled = false
            tvDevOutput.text = getString(R.string.dev_running_cmd, cmd)

            shell.execWhenReady(cmd) { output ->
                runOnUiThread {
                    btnDevRun.isEnabled = true
                    val result = getString(R.string.dev_output_format, cmd, "─".repeat(40), output.ifBlank { getString(R.string.dev_no_output) })
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
        val columns = resources.getInteger(R.integer.app_grid_columns)
        binding.rvApps.layoutManager = GridLayoutManager(this, columns)
        adapter = AppAdapter { item -> showAppActionsBottomSheet(item) }
        binding.rvApps.adapter = adapter

        // ── Dual filter chip ─────────────────────────────────────────────────
        val chipDualFilter = binding.chipDualFilter
        val filterStates = listOf(
            AppAdapter.DualFilter.ALL       to getString(R.string.filter_all),
            AppAdapter.DualFilter.DUAL_ONLY to getString(R.string.filter_in_workspace),
            AppAdapter.DualFilter.MAIN_ONLY to getString(R.string.filter_main_only)
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
                    currentFilter = AppRepository.AppFilter.ALL
                    showAppList()
                    loadAppsUser0()
                    true
                }
                R.id.nav_user -> {
                    currentFilter = AppRepository.AppFilter.USER_ONLY
                    showAppList()
                    loadAppsUser0()
                    true
                }
                R.id.nav_system -> {
                    currentFilter = AppRepository.AppFilter.SYSTEM_ONLY
                    showAppList()
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
        binding.rvWorkspaces.layoutManager = LinearLayoutManager(this)
        binding.rvWorkspaces.adapter       = wsAdapter
        binding.rvWorkspaces.isNestedScrollingEnabled = false

        // ── Create workspace button ──────────────────────────────────────────
        binding.btnCreateWorkspace.setOnClickListener {
            if (!requireShizukuOrToast()) return@setOnClickListener
            val name = wsRepo.suggestName(cachedWorkspaces, "Work")
            doCreateWorkspace(name, "managed")
        }

        // ── Create clone workspace button ────────────────────────────────────
        binding.btnCreateCloneWorkspace.setOnClickListener {
            if (!requireShizukuOrToast()) return@setOnClickListener
            val name = wsRepo.suggestName(cachedWorkspaces, "Clone")
            doCreateWorkspace(name, "clone")
        }

        // ── Refresh workspaces button ────────────────────────────────────────
        binding.btnRefreshWorkspaces.setOnClickListener {
            if (::wsRepo.isInitialized) loadWorkspaces()
        }
    }

    private fun setupAboutVersion() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pInfo.versionName
            val appName = getString(R.string.app_name)
            binding.tvAppVersion.text = getString(R.string.app_name_version, appName, version)
        } catch (e: Exception) {
            binding.tvAppVersion.text = getString(R.string.app_name_version, getString(R.string.app_name), "1.1")
        }
    }

    private fun showAppList() {
        binding.layoutAppList.isVisible = true
        binding.layoutSettings.isVisible = false
        invalidateOptionsMenu() // Refresh search button visibility
    }

    private fun showSettings() {
        binding.layoutAppList.isVisible = false
        binding.layoutSettings.isVisible = true
        title = getString(R.string.settings)
        if (::wsRepo.isInitialized) loadWorkspaces()
        invalidateOptionsMenu() // Refresh search button visibility
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Shimmer
    // ════════════════════════════════════════════════════════════════════════

    private fun scheduleShowShimmer() {
        cancelShowShimmer()
        showShimmerRunnable = Runnable {
            binding.rvApps.isVisible = false
            binding.shimmerOverlay.isVisible = true
            binding.shimmerOverlay.startShimmer()
        }
        uiHandler.postDelayed(showShimmerRunnable!!, SHIMMER_DELAY_MS)
    }

    private fun hideShimmerNow() {
        cancelShowShimmer()
        binding.shimmerOverlay.stopShimmer()
        binding.shimmerOverlay.isVisible = false
        binding.rvApps.isVisible = true
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
                    Toast.makeText(this, getString(R.string.shizuku_granted), Toast.LENGTH_SHORT).show()
                    initializeShellAndUpdate()
                } else {
                    Toast.makeText(this, getString(R.string.shizuku_required), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, getString(R.string.error_initializing, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun requireShizukuOrToast(silent: Boolean = false): Boolean {
        val ok = Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
                ::shell.isInitialized
        if (!ok && !silent) Toast.makeText(this, getString(R.string.shizuku_required), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, getString(R.string.error_loading_apps, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateTitle(list: List<AppItem>) {
        if (binding.layoutSettings.isVisible) return

        val filterText = when (currentFilter) {
            AppRepository.AppFilter.ALL         -> getString(R.string.filter_all)
            AppRepository.AppFilter.USER_ONLY   -> getString(R.string.filter_user)
            AppRepository.AppFilter.SYSTEM_ONLY -> getString(R.string.filter_system)
        }
        val inWorkspace = list.count { it.isDual }
        val formatted = getString(R.string.app_title_format, filterText, list.size, inWorkspace)

        // Split the string by the newline character
        val parts = formatted.split("\n", limit = 2)

        // Set the first part as the main title
        supportActionBar?.title = parts[0]

        // Set the second part (the one in parentheses) as the subtitle
        // Subtitles are automatically smaller and appear on the second line
        if (parts.size > 1) {
            supportActionBar?.subtitle = parts[1]
        } else {
            supportActionBar?.subtitle = null
        }
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
                binding.tvNoWorkspacesInfo.isVisible = managed.isEmpty()
            }
        }
    }

    private fun doCreateWorkspace(name: String, type: String) {
        binding.btnCreateWorkspace.isEnabled = false
        binding.btnCreateCloneWorkspace.isEnabled = false
        Toast.makeText(this, getString(R.string.creating_workspace_toast, name), Toast.LENGTH_SHORT).show()

        wsRepo.createWorkspace(name, type) { success, userId, output ->
            runOnUiThread {
                binding.btnCreateWorkspace.isEnabled = true
                binding.btnCreateCloneWorkspace.isEnabled = true
                if (success) {
                    // Auto-start new workspace so it's immediately usable
                    wsRepo.startWorkspace(userId) { _, _ ->
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.workspace_created_toast, name, userId), Toast.LENGTH_SHORT).show()
                            loadWorkspaces()
                            updateAllWorkspaceStatuses()
                        }
                    }
                } else {
                    Toast.makeText(this, getString(R.string.failed_to_create_workspace, output), Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, getString(R.string.removing_workspace_toast, ws.displayName), Toast.LENGTH_SHORT).show()
        wsRepo.removeWorkspace(ws.userId) { success, output ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, getString(R.string.workspace_removed_toast), Toast.LENGTH_SHORT).show()
                    loadWorkspaces()
                    updateAllWorkspaceStatuses()
                } else {
                    Toast.makeText(this, getString(R.string.failed_generic, output), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun doStartWorkspace(ws: WorkspaceInfo) {
        Toast.makeText(this, getString(R.string.starting_workspace_toast, ws.displayName), Toast.LENGTH_SHORT).show()
        wsRepo.startWorkspace(ws.userId) { success, output ->
            runOnUiThread {
                if (success) {
                    loadWorkspaces()
                } else {
                    Toast.makeText(this, getString(R.string.failed_generic, output), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun doStopWorkspace(ws: WorkspaceInfo) {
        Toast.makeText(this, getString(R.string.stopping_workspace_toast, ws.displayName), Toast.LENGTH_SHORT).show()
        wsRepo.stopWorkspace(ws.userId) { success, output ->
            runOnUiThread {
                if (success) {
                    loadWorkspaces()
                } else {
                    Toast.makeText(this, getString(R.string.failed_generic, output), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, getString(R.string.no_launcher_found), Toast.LENGTH_SHORT).show()
            return
        }
        wsRepo.launchInWorkspace(userId, component) { success, output ->
            runOnUiThread {
                if (!success) Toast.makeText(this, getString(R.string.launch_failed, output), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openAppInfo(userId: Int, packageName: String) {
        if (!requireShizukuOrToast()) return
        wsRepo.openAppInfoInWorkspace(userId, packageName) { success, output ->
            runOnUiThread {
                if (!success) Toast.makeText(this, getString(R.string.failed_generic, output), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  App-actions bottom sheet
    // ════════════════════════════════════════════════════════════════════════

    private fun showAppActionsBottomSheet(item: AppItem) {
        val sheetBinding = BottomSheetAppActionsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this).apply {
            setContentView(sheetBinding.root)
        }

        // Header Setup
        with(sheetBinding) {
            ivAppIcon.setImageDrawable(item.icon)
            tvAppName.text = item.label
            tvPackageName.text = item.packageName
            btnCancel.setOnClickListener { dialog.dismiss() }
        }

        // Main Space Buttons
        val shizukuReady = requireShizukuOrToast(silent = true)
        with(sheetBinding) {
            btnLaunchMain.isEnabled = shizukuReady
            btnAppInfoMain.isEnabled = shizukuReady
            btnLaunchMain.setOnClickListener  { launchApp(0, item.packageName) }
            btnAppInfoMain.setOnClickListener { openAppInfo(0, item.packageName) }
        }

        // Workspaces Logic
        val managedWorkspaces = cachedWorkspaces.filter { !it.isMainUser }
        sheetBinding.tvNoWorkspaces.isVisible = managedWorkspaces.isEmpty()
        sheetBinding.layoutWorkspaceActions.removeAllViews()

        managedWorkspaces.forEach { ws ->
            val rowBinding = ItemWorkspaceActionBinding.inflate(layoutInflater, sheetBinding.layoutWorkspaceActions, true)
            bindWorkspaceActionRow(rowBinding, ws, item, dialog, sheetBinding)
        }

        dialog.show()
    }

    /**
     * Configures a single workspace row inside the app-actions bottom sheet.
     * Handles install ↔ uninstall toggle and launch / app-info buttons.
     */
    private fun bindWorkspaceActionRow(
        row: ItemWorkspaceActionBinding,
        ws: WorkspaceInfo,
        item: AppItem,
        dialog: BottomSheetDialog,
        sheetBinding: BottomSheetAppActionsBinding
    ) {
        var isInstalled = item.installedUserIds.contains(ws.userId)
        val running = ws.isRunning

        row.tvWsActionName.text = buildString {
            append(ws.displayName)
            append("  (User ${ws.userId}")
            if (!running) append(" · ${getString(R.string.stopped)}")
            append(")")
        }

        fun updateUiState(installed: Boolean) {
            isInstalled = installed
            with(row) {
                chipWsInstalled.text = getString(if (installed) R.string.installed else R.string.not_installed)
                chipWsInstalled.isChecked = installed
                btnWsInstallToggle.text = getString(if (installed) R.string.uninstall else R.string.install)
                btnWsLaunch.isEnabled = installed && running
                btnWsAppInfo.isEnabled = installed
            }
        }

        updateUiState(isInstalled)

        row.btnWsInstallToggle.setOnClickListener {
            val actionText = row.btnWsInstallToggle.text
            sheetBinding.layoutProgress.isVisible = true
            sheetBinding.tvProgressText.text = actionText
            row.btnWsInstallToggle.isEnabled = false

            val callback: (Boolean, String) -> Unit = { success, msg ->
                runOnUiThread {
                    sheetBinding.layoutProgress.isVisible = false
                    row.btnWsInstallToggle.isEnabled = true
                    if (success) {
                        updateUiState(!isInstalled)
                        updateAllWorkspaceStatuses()
                    } else {
                        Toast.makeText(this, getString(R.string.failed_generic, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }

            if (isInstalled) {
                wsRepo.uninstallFromWorkspace(ws.userId, item.packageName, callback)
            } else {
                if (!running) {
                    wsRepo.startWorkspace(ws.userId) { _, _ ->
                        wsRepo.installToWorkspace(ws.userId, item.packageName, callback)
                    }
                } else {
                    wsRepo.installToWorkspace(ws.userId, item.packageName, callback)
                }
            }
        }

        row.btnWsLaunch.setOnClickListener {
            dialog.dismiss()
            launchApp(ws.userId, item.packageName)
        }

        row.btnWsAppInfo.setOnClickListener {
            openAppInfo(ws.userId, item.packageName)
        }
    }
}
