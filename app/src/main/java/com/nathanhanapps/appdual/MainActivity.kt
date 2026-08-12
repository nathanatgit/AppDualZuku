package com.nathanhanapps.appdual

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nathanhanapps.appdual.databinding.ActivityMainBinding
import com.nathanhanapps.appdual.databinding.BottomSheetAppActionsBinding
import com.nathanhanapps.appdual.databinding.BottomSheetBatchActionsBinding
import com.nathanhanapps.appdual.databinding.DialogExportFilenameBinding
import com.nathanhanapps.appdual.databinding.DialogTappableMessageBinding
import com.nathanhanapps.appdual.databinding.ItemWorkspaceActionBinding
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
    private lateinit var shell:     IShellExecutor
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

    // ── Batch management ─────────────────────────────────────────────────────
    private var batchDialog: BottomSheetDialog? = null
    private var pendingExportJson: String? = null
    private var pendingExportCount: Int = 0

    // Must be registered during construction (before onCreate), per ComponentActivity contract.
    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeExportToUri(uri) else pendingExportJson = null }

    private val importDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readImportFromUri(it) } }

    companion object {
        private const val REQUEST_SHIZUKU_PERMISSION = 1234
        private const val SHIMMER_DELAY_MS = 400L

        // Used to sanity-check that a "clone" selection looks like a full workspace app
        // list before deleting everything else out of a target workspace. Each inner list
        // is a set of interchangeable package names satisfying one requirement - AOSP vs.
        // GMS-flavored builds ship some of these under different names. This no longer
        // hard-blocks the clone (see showIncompleteSelectionDialog) - it's a warning gate.
        private val CLONE_REQUIRED_PACKAGE_GROUPS = listOf(
            listOf("com.android.settings"),
            listOf("com.android.providers.settings"),
            listOf("com.android.providers.media", "com.android.providers.media.module")
        )

        // Reference list shown only after the 5-tap reveal - broader than what's actually
        // enforced above, for the user's own context on what a "full" workspace app set
        // typically includes. Deliberately excludes Camera: its package name varies too
        // much by OEM to be a useful reference, let alone an enforced check.
        private val CLONE_REFERENCE_PACKAGE_GROUPS = CLONE_REQUIRED_PACKAGE_GROUPS + listOf(
            listOf("com.android.documentsui"),
            listOf("com.android.externalstorage"),
            listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller"),
            listOf("com.android.packageinstaller", "com.google.android.packageinstaller")
        )
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
        initializeExecution()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        tintOverflowIcon()

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

        // Selection-shortcut items only make sense in batch mode, on the app list
        val inBatch = binding.layoutAppList.isVisible && ::adapter.isInitialized && adapter.batchMode
        menu.findItem(R.id.action_select_all).isVisible = inBatch
        menu.findItem(R.id.action_invert_selection).isVisible = inBatch
        menu.findItem(R.id.action_deselect_all).isVisible = inBatch

        return true
    }

    /** Matches the toolbar's "⋮" overflow icon to the bottom nav's (unselected) icon color. */
    private fun tintOverflowIcon() {
        val tint = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        binding.toolbar.overflowIcon?.mutate()?.setTint(tint)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select_all -> { adapter.selectAll(); true }
            R.id.action_invert_selection -> { adapter.invertSelection(); true }
            R.id.action_deselect_all -> { adapter.deselectAll(); true }
            else -> super.onOptionsItemSelected(item)
        }
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

    /**
     * Posts to [bg], skipping the task if the activity has already been torn down.
     * Async chains (e.g. querying packages per workspace) can outlive the activity -
     * without this guard, the final `bg.execute` throws RejectedExecutionException
     * once onDestroy() has shut the executor down (easiest to hit with clone
     * workspaces, whose package lists are large enough to make the query slow).
     */
    private fun runBg(task: () -> Unit) {
        if (bg.isShutdown) return
        bg.execute(task)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UI Setup
    // ════════════════════════════════════════════════════════════════════════

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)

        // ── App list RecyclerView ────────────────────────────────────────────
        val columns = resources.getInteger(R.integer.app_grid_columns)
        binding.rvApps.layoutManager = GridLayoutManager(this, columns)
        adapter = AppAdapter(
            onItemClick = { item -> showAppActionsBottomSheet(item) },
            onLongPress = { item -> enterBatchModeAndSelect(item) },
            onSelectionChanged = { selected -> updateBatchFab(selected) }
        )
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
            if (!requireShellOrToast()) return@setOnClickListener
            val name = wsRepo.suggestName(cachedWorkspaces, "Work")
            doCreateWorkspace(name, "managed")
        }

        // ── Create clone workspace button ────────────────────────────────────
        binding.btnCreateCloneWorkspace.setOnClickListener {
            if (!requireShellOrToast()) return@setOnClickListener
            val name = wsRepo.suggestName(cachedWorkspaces, "Clone")
            doCreateWorkspace(name, "clone")
        }

        // ── Refresh workspaces button ────────────────────────────────────────
        binding.btnRefreshWorkspaces.setOnClickListener {
            if (::wsRepo.isInitialized) loadWorkspaces()
        }

        // ── Execution mode (root vs. Shizuku) card ───────────────────────────
        setupExecutionModeCard()

        // ── Batch management ─────────────────────────────────────────────────
        binding.chipBatchMode.setOnClickListener {
            val newMode = !adapter.batchMode
            adapter.setBatchMode(newMode)
            binding.chipBatchMode.isChecked = newMode
            invalidateOptionsMenu()
        }
        binding.fabBatchActions.setOnClickListener { showBatchActionsBottomSheet() }
        // Set in code, not XML: ExtendedFloatingActionButton's default Material3 style
        // applies its own icon tint via a theme overlay that can outrank a plain
        // app:iconTint attribute, so only an explicit post-inflation set is reliable.
        binding.fabBatchActions.iconTint = ColorStateList.valueOf(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        )
    }

    private fun enterBatchModeAndSelect(item: AppItem) {
        if (!adapter.batchMode) {
            adapter.setBatchMode(true)
            binding.chipBatchMode.isChecked = true
            invalidateOptionsMenu()
        }
        adapter.toggleSelection(item.packageName)
    }

    private fun updateBatchFab(selected: Set<String>) {
        if (selected.isEmpty()) {
            binding.fabBatchActions.isVisible = false
        } else {
            binding.fabBatchActions.text = getString(R.string.batch_fab_selected, selected.size)
            binding.fabBatchActions.isVisible = true
            // Setting .text alone doesn't re-layout an ExtendedFloatingActionButton that
            // hasn't gone through an extend()/shrink() transition yet - without this it
            // stays in its initial icon-only "shrunk" state and the text never shows.
            binding.fabBatchActions.extend()
        }
    }

    private fun setupExecutionModeCard() {
        binding.switchUseRoot.isChecked = Prefs.useRoot(this)
        binding.switchUseRoot.setOnCheckedChangeListener { _, checked ->
            if (Prefs.useRoot(this) == checked) return@setOnCheckedChangeListener
            Prefs.setUseRoot(this, checked)
            reinitializeExecution()
        }
        refreshRootStatusText()
    }

    private fun refreshRootStatusText() {
        binding.tvRootStatus.setText(R.string.root_checking)
        runBg {
            val available = RootShellClient.isRootAvailable()
            runOnUiThread {
                binding.tvRootStatus.setText(
                    if (available) R.string.root_detected_yes else R.string.root_detected_no
                )
            }
        }
    }

    private fun setupAboutVersion() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pInfo.versionName
            val appName = getString(R.string.app_name)
            binding.tvAppVersion.text = getString(R.string.app_name_version, appName, version)
        } catch (e: Exception) {
            binding.tvAppVersion.text = getString(R.string.app_name_version, getString(R.string.app_name), "1.4")
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
            populateSkeletonList()
            binding.rvApps.isVisible = false
            binding.shimmerOverlay.isVisible = true
            binding.shimmerOverlay.startShimmer()
        }
        uiHandler.postDelayed(showShimmerRunnable!!, SHIMMER_DELAY_MS)
    }

    /**
     * Fills [skeletonList] with just enough placeholder rows to cover the visible area,
     * computed from the container's actual measured height instead of a fixed count -
     * a fixed count of 8 left blank space below it on tall screens. Runs once: by the
     * time this is first called (from the delayed shimmer runnable above) the container
     * has already been through a layout pass, so its height is reliably non-zero.
     */
    private fun populateSkeletonList() {
        val container = binding.skeletonList
        if (container.childCount > 0) return

        val containerHeightPx = binding.appsListContainer.height
        val itemHeightPx = (72 * resources.displayMetrics.density).toInt()
        if (containerHeightPx <= 0 || itemHeightPx <= 0) return

        val count = containerHeightPx / itemHeightPx + 2 // +2: partial trailing row + safety margin
        repeat(count) {
            layoutInflater.inflate(R.layout.item_app_skeleton, container, true)
        }
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

    // ════════════════════════════════════════════════════════════════════════
    //  Execution mode (Shizuku vs. root) selection
    // ════════════════════════════════════════════════════════════════════════

    private fun initializeExecution() {
        if (Prefs.useRoot(this)) {
            initRootShellAndUpdate()
        } else {
            checkShizukuAndInitialize()
        }
    }

    private fun initRootShellAndUpdate() {
        if (isInitialized) return
        runBg {
            val available = RootShellClient.isRootAvailable()
            runOnUiThread {
                if (!available) {
                    Toast.makeText(this, getString(R.string.root_unavailable), Toast.LENGTH_LONG).show()
                    Prefs.setUseRoot(this, false)
                    binding.switchUseRoot.isChecked = false
                    checkShizukuAndInitialize()
                    return@runOnUiThread
                }
                try {
                    shell  = RootShellClient(this)
                    wsRepo = WorkspaceRepository(shell)
                    isInitialized = true
                    updateAllWorkspaceStatuses()
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.error_initializing, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Tears down whichever backend is active and switches to the other. */
    private fun reinitializeExecution() {
        if (::shell.isInitialized) runCatching { shell.unbind() }
        isInitialized = false
        initializeExecution()
    }

    private fun requireShellOrToast(silent: Boolean = false): Boolean {
        val useRoot = Prefs.useRoot(this)
        val ok = ::shell.isInitialized && (
            useRoot || (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        )
        if (!ok && !silent) {
            val msgRes = if (useRoot) R.string.root_required else R.string.shizuku_required
            Toast.makeText(this, getString(msgRes), Toast.LENGTH_SHORT).show()
        }
        return ok
    }

    // ════════════════════════════════════════════════════════════════════════
    //  App list loading
    // ════════════════════════════════════════════════════════════════════════

    private fun loadAppsUser0() {
        runOnUiThread { scheduleShowShimmer() }
        runBg {
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
        if (!Prefs.useRoot(this) && (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)) return

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
        runBg {
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
        if (!requireShellOrToast()) return
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
        if (!requireShellOrToast()) return
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
        val shellReady = requireShellOrToast(silent = true)
        with(sheetBinding) {
            btnLaunchMain.isEnabled = shellReady
            btnAppInfoMain.isEnabled = shellReady
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

        row.tvWsActionName.text = if (running) {
            getString(R.string.workspace_user_label, ws.displayName, ws.userId)
        } else {
            getString(R.string.workspace_user_label_stopped, ws.displayName, ws.userId, getString(R.string.stopped))
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

    // ════════════════════════════════════════════════════════════════════════
    //  Batch management
    // ════════════════════════════════════════════════════════════════════════

    private fun showBatchActionsBottomSheet() {
        val selected = adapter.selectedPackages()
        if (selected.isEmpty()) return

        val sheetBinding = BottomSheetBatchActionsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this).apply { setContentView(sheetBinding.root) }
        batchDialog = dialog
        dialog.setOnDismissListener { if (batchDialog === dialog) batchDialog = null }

        sheetBinding.tvBatchSelectedCount.text = getString(R.string.batch_selected_count, selected.size)

        val managedWorkspaces = cachedWorkspaces.filter { !it.isMainUser }
        sheetBinding.tvBatchNoWorkspaces.isVisible = managedWorkspaces.isEmpty()
        sheetBinding.layoutBatchWorkspaces.removeAllViews()

        val workspaceCheckBoxes = mutableListOf<Pair<Int, MaterialCheckBox>>()
        managedWorkspaces.forEach { ws ->
            val cb = layoutInflater.inflate(
                R.layout.item_workspace_checkbox, sheetBinding.layoutBatchWorkspaces, false
            ) as MaterialCheckBox
            cb.text = getString(R.string.workspace_user_label, ws.displayName, ws.userId)
            sheetBinding.layoutBatchWorkspaces.addView(cb)
            workspaceCheckBoxes.add(ws.userId to cb)
        }

        fun selectedWorkspaceIds(): List<Int> =
            workspaceCheckBoxes.filter { it.second.isChecked }.map { it.first }

        // Shared by whichever operation is currently running (only one can run at a time,
        // since the action buttons are disabled while one is in progress).
        val cancelled = AtomicBoolean(false)

        sheetBinding.btnBatchInstall.setOnClickListener {
            cancelled.set(false)
            performBatchOperation(install = true, selected.toList(), selectedWorkspaceIds(), sheetBinding, cancelled)
        }
        sheetBinding.btnBatchUninstall.setOnClickListener {
            cancelled.set(false)
            performBatchOperation(install = false, selected.toList(), selectedWorkspaceIds(), sheetBinding, cancelled)
        }
        sheetBinding.btnBatchClone.setOnClickListener {
            cancelled.set(false)
            confirmAndPerformClone(selected, selectedWorkspaceIds(), sheetBinding, cancelled)
        }
        sheetBinding.btnBatchExport.setOnClickListener { showExportDialog(selected) }
        sheetBinding.btnBatchImport.setOnClickListener { importDocumentLauncher.launch(arrayOf("*/*")) }
        sheetBinding.btnBatchCancel.setOnClickListener {
            if (sheetBinding.layoutBatchProgress.isVisible) {
                // A batch op is running: stop queuing further jobs instead of just hiding the
                // sheet, since the op previously kept running invisibly in the background.
                cancelled.set(true)
            } else {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun setBatchButtonsEnabled(sheetBinding: BottomSheetBatchActionsBinding, enabled: Boolean) {
        sheetBinding.btnBatchInstall.isEnabled = enabled
        sheetBinding.btnBatchUninstall.isEnabled = enabled
        sheetBinding.btnBatchClone.isEnabled = enabled
        sheetBinding.btnBatchExport.isEnabled = enabled
        sheetBinding.btnBatchImport.isEnabled = enabled
    }

    private fun updateBatchProgressText(sheetBinding: BottomSheetBatchActionsBinding, phase: String, done: Int, total: Int) {
        sheetBinding.tvBatchProgressText.text = getString(R.string.batch_progress_format, phase, done, total)
    }

    /**
     * Installs/uninstalls [packages] into each of [workspaceIds]. "Smart": an install is
     * skipped where the app is already present, an uninstall is skipped where it isn't -
     * so re-running the same batch op is always a safe no-op for already-settled pairs.
     * Runtime failures (e.g. Shizuku/ADB refusing to uninstall a protected package) are
     * folded into the same skip count rather than aborting the batch.
     */
    private fun performBatchOperation(
        install: Boolean,
        packages: List<String>,
        workspaceIds: List<Int>,
        sheetBinding: BottomSheetBatchActionsBinding,
        cancelled: AtomicBoolean
    ) {
        if (workspaceIds.isEmpty()) {
            Toast.makeText(this, getString(R.string.batch_no_workspace_selected), Toast.LENGTH_SHORT).show()
            return
        }

        val pkgByName = cachedFullList.associateBy { it.packageName }
        val jobs = mutableListOf<Pair<String, Int>>() // (packageName, userId)
        var preSkipped = 0
        for (pkg in packages) {
            val installedIn = pkgByName[pkg]?.installedUserIds ?: emptySet()
            for (uid in workspaceIds) {
                val alreadyInstalled = uid in installedIn
                val shouldRun = if (install) !alreadyInstalled else alreadyInstalled
                if (shouldRun) jobs.add(pkg to uid) else preSkipped++
            }
        }

        val phase = getString(if (install) R.string.batch_installing else R.string.batch_uninstalling)
        sheetBinding.layoutBatchProgress.isVisible = true
        updateBatchProgressText(sheetBinding, phase, 0, jobs.size)
        setBatchButtonsEnabled(sheetBinding, false)

        fun runJobs() {
            runBatchJobsSequentially(jobs, 0, install, successCount = 0, skippedCount = preSkipped, cancelled, sheetBinding) { success, skipped ->
                runOnUiThread {
                    sheetBinding.layoutBatchProgress.isVisible = false
                    setBatchButtonsEnabled(sheetBinding, true)
                    val msg = if (install) getString(R.string.batch_install_summary, success, skipped)
                              else getString(R.string.batch_uninstall_summary, success, skipped)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    updateAllWorkspaceStatuses()
                }
            }
        }

        if (install) {
            // Best-effort: a stopped workspace can't receive installs, so start every target first.
            startWorkspacesThenRun(workspaceIds) { runJobs() }
        } else {
            runJobs()
        }
    }

    private fun startWorkspacesThenRun(workspaceIds: List<Int>, onDone: () -> Unit) {
        fun step(index: Int) {
            if (index >= workspaceIds.size) {
                onDone()
                return
            }
            wsRepo.startWorkspace(workspaceIds[index]) { _, _ -> step(index + 1) }
        }
        step(0)
    }

    private fun runBatchJobsSequentially(
        jobs: List<Pair<String, Int>>,
        index: Int,
        install: Boolean,
        successCount: Int,
        skippedCount: Int,
        cancelled: AtomicBoolean,
        sheetBinding: BottomSheetBatchActionsBinding,
        onDone: (Int, Int) -> Unit
    ) {
        if (index >= jobs.size || cancelled.get()) {
            // Anything left un-run (including because the user cancelled) counts as skipped.
            onDone(successCount, skippedCount + (jobs.size - index).coerceAtLeast(0))
            return
        }
        val (pkg, userId) = jobs[index]
        val callback: (Boolean, String) -> Unit = { success, _ ->
            val nextSuccess = successCount + if (success) 1 else 0
            val nextSkipped  = skippedCount + if (success) 0 else 1
            runOnUiThread {
                val phase = getString(if (install) R.string.batch_installing else R.string.batch_uninstalling)
                updateBatchProgressText(sheetBinding, phase, index + 1, jobs.size)
            }
            runBatchJobsSequentially(jobs, index + 1, install, nextSuccess, nextSkipped, cancelled, sheetBinding, onDone)
        }
        if (install) {
            wsRepo.installToWorkspace(userId, pkg, callback)
        } else {
            wsRepo.uninstallFromWorkspace(userId, pkg, callback)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Batch clone: make target workspace(s) match the selection exactly
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Guard against cloning a partial/accidental selection over a whole workspace: a real
     * "full app list" export always includes a few core system packages, so their absence
     * is a strong signal the selection is a subset, not a full snapshot. This only warns -
     * the user can push through via the red "continue anyway" button.
     */
    private fun confirmAndPerformClone(
        packages: Set<String>,
        workspaceIds: List<Int>,
        sheetBinding: BottomSheetBatchActionsBinding,
        cancelled: AtomicBoolean
    ) {
        if (workspaceIds.isEmpty()) {
            Toast.makeText(this, getString(R.string.batch_no_workspace_selected), Toast.LENGTH_SHORT).show()
            return
        }

        if (selectionLooksLikeFullWorkspace(packages)) {
            showCloneConfirmDialog(packages, workspaceIds, sheetBinding, cancelled)
        } else {
            showIncompleteSelectionDialog { showCloneConfirmDialog(packages, workspaceIds, sheetBinding, cancelled) }
        }
    }

    private fun selectionLooksLikeFullWorkspace(packages: Set<String>): Boolean =
        CLONE_REQUIRED_PACKAGE_GROUPS.all { group -> group.any { it in packages } }

    private fun showCloneConfirmDialog(
        packages: Set<String>,
        workspaceIds: List<Int>,
        sheetBinding: BottomSheetBatchActionsBinding,
        cancelled: AtomicBoolean
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clone_confirm_title)
            .setMessage(R.string.clone_confirm_message)
            .setPositiveButton(R.string.clone_button) { _, _ -> runCloneOperation(packages, workspaceIds, sheetBinding, cancelled) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Deliberately doesn't say which packages are being checked up front - showing that
     * list unprompted would teach anyone probing the warning exactly what to add to slip
     * past it. Tapping the message once reveals the reference list on demand instead.
     */
    private fun showIncompleteSelectionDialog(onContinueAnyway: () -> Unit) {
        val messageBinding = DialogTappableMessageBinding.inflate(layoutInflater)
        messageBinding.tvDialogMessage.text = getString(R.string.clone_incomplete_selection_message)

        var revealed = false
        messageBinding.tvDialogMessage.setOnClickListener {
            if (revealed) return@setOnClickListener
            revealed = true
            val referenceList = CLONE_REFERENCE_PACKAGE_GROUPS.joinToString("\n") { it.joinToString(" / ") }
            messageBinding.tvDialogMessage.text = getString(R.string.clone_reference_list_prompt, referenceList)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clone_incomplete_selection_title)
            .setView(messageBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_anyway) { _, _ -> onContinueAnyway() }
            .show()

        // Tint the override button red after show() so the button exists in the view tree
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
        }
    }

    private data class CloneJob(val userId: Int, val isInstall: Boolean, val packageName: String)

    private fun runCloneOperation(
        packages: Set<String>,
        workspaceIds: List<Int>,
        sheetBinding: BottomSheetBatchActionsBinding,
        cancelled: AtomicBoolean
    ) {
        sheetBinding.layoutBatchProgress.isVisible = true
        sheetBinding.tvBatchProgressText.setText(R.string.batch_clone_preparing)
        setBatchButtonsEnabled(sheetBinding, false)

        startWorkspacesThenRun(workspaceIds) {
            // Fetch every target workspace's actual current package list first (not the
            // cached main-space view) so the diff is correct, and so the total job count -
            // and therefore the N/Total progress below - is known up front.
            collectCloneJobs(workspaceIds, 0, packages, mutableListOf()) { jobs ->
                val phase = getString(R.string.batch_cloning)
                updateBatchProgressText(sheetBinding, phase, 0, jobs.size)
                runCloneJobsSequentially(jobs, 0, installed = 0, uninstalled = 0, skipped = 0, cancelled, sheetBinding) { i, u, s ->
                    runOnUiThread {
                        sheetBinding.layoutBatchProgress.isVisible = false
                        setBatchButtonsEnabled(sheetBinding, true)
                        Toast.makeText(this, getString(R.string.batch_clone_summary, i, u, s), Toast.LENGTH_LONG).show()
                        updateAllWorkspaceStatuses()
                    }
                }
            }
        }
    }

    private fun collectCloneJobs(
        workspaceIds: List<Int>,
        index: Int,
        packages: Set<String>,
        acc: MutableList<CloneJob>,
        onCollected: (List<CloneJob>) -> Unit
    ) {
        if (index >= workspaceIds.size) {
            onCollected(acc)
            return
        }
        val userId = workspaceIds[index]
        wsRepo.getInstalledPackages(userId) { currentPkgs ->
            (packages - currentPkgs).forEach { acc.add(CloneJob(userId, true, it)) }
            (currentPkgs - packages).forEach { acc.add(CloneJob(userId, false, it)) }
            collectCloneJobs(workspaceIds, index + 1, packages, acc, onCollected)
        }
    }

    private fun runCloneJobsSequentially(
        jobs: List<CloneJob>,
        index: Int,
        installed: Int,
        uninstalled: Int,
        skipped: Int,
        cancelled: AtomicBoolean,
        sheetBinding: BottomSheetBatchActionsBinding,
        onDone: (Int, Int, Int) -> Unit
    ) {
        if (index >= jobs.size || cancelled.get()) {
            onDone(installed, uninstalled, skipped + (jobs.size - index).coerceAtLeast(0))
            return
        }
        val job = jobs[index]
        val callback: (Boolean, String) -> Unit = { success, _ ->
            val (i, u, s) = when {
                success && job.isInstall  -> Triple(installed + 1, uninstalled, skipped)
                success && !job.isInstall -> Triple(installed, uninstalled + 1, skipped)
                else                       -> Triple(installed, uninstalled, skipped + 1)
            }
            runOnUiThread {
                updateBatchProgressText(sheetBinding, getString(R.string.batch_cloning), index + 1, jobs.size)
            }
            runCloneJobsSequentially(jobs, index + 1, i, u, s, cancelled, sheetBinding, onDone)
        }
        if (job.isInstall) {
            wsRepo.installToWorkspace(job.userId, job.packageName, callback)
        } else {
            wsRepo.uninstallFromWorkspace(job.userId, job.packageName, callback)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Batch list import / export (Storage Access Framework)
    // ════════════════════════════════════════════════════════════════════════

    private fun showExportDialog(selected: Set<String>) {
        val dialogBinding = DialogExportFilenameBinding.inflate(layoutInflater)
        dialogBinding.etExportFileName.setText(PackageListIO.defaultFileName())
        dialogBinding.etExportFileName.text?.let { dialogBinding.etExportFileName.setSelection(it.length) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val typed = dialogBinding.etExportFileName.text?.toString()?.trim()
                val name = if (typed.isNullOrBlank()) PackageListIO.defaultFileName() else typed
                pendingExportJson = PackageListIO.serialize(selected)
                pendingExportCount = selected.size
                exportDocumentLauncher.launch(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun writeExportToUri(uri: Uri) {
        val json = pendingExportJson
        val count = pendingExportCount
        pendingExportJson = null
        if (json == null) return

        runBg {
            try {
                val stream = contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("openOutputStream returned null")
                stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.batch_export_success, count), Toast.LENGTH_SHORT).show()
                    batchDialog?.dismiss()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.batch_export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun readImportFromUri(uri: Uri) {
        runBg {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("openInputStream returned null")
                val text = stream.use { it.bufferedReader().readText() }

                val importedPkgs = PackageListIO.deserialize(text).toSet()
                val installedOnDevice = cachedFullList.map { it.packageName }.toSet()
                val matched = importedPkgs.intersect(installedOnDevice)
                val notFound = importedPkgs.size - matched.size

                runOnUiThread {
                    adapter.setBatchMode(true)
                    binding.chipBatchMode.isChecked = true
                    invalidateOptionsMenu()
                    adapter.setSelectedPackages(matched)
                    Toast.makeText(this, getString(R.string.batch_import_success, matched.size, notFound), Toast.LENGTH_LONG).show()
                    batchDialog?.dismiss()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.batch_import_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
