package com.nevergrowold.holdhightheshop

import android.Manifest
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nevergrowold.holdhightheshop.data.AppRepository
import com.nevergrowold.holdhightheshop.media.ImagePipeline
import com.nevergrowold.holdhightheshop.security.IntentSecurity
import com.nevergrowold.holdhightheshop.system.MarketplaceBroadcasts
import com.nevergrowold.holdhightheshop.system.MarketplaceEventReceiver
import com.nevergrowold.holdhightheshop.system.MarketplaceSyncService
import com.nevergrowold.holdhightheshop.system.MeetupCountdownService
import com.nevergrowold.holdhightheshop.system.NetworkStateMonitor
import com.nevergrowold.holdhightheshop.system.NotificationHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Single-host shell for the four primary tabs (Home, Market, Chats, Profile).
 *
 * Security & Component Architecture:
 * - **Auth Gate**: Enforces verified `@students.uc-bcf.edu.ph` session on BOTH `onCreate` and
 *   `onNewIntent` so external deep links, searches, or `ACTION_SEND` intents can never bypass login.
 * - **IntentSanitizer**: Protects incoming `ACTION_SEND` against `file://` and self-FileProvider exfiltration.
 * - **Bound Service**: Binds [MeetupCountdownService] for live upcoming meetup/loan countdowns.
 * - **Foreground Service**: Triggers [MarketplaceSyncService] for clean background deal/reminder maintenance.
 * - **Dynamic BroadcastReceiver**: Registers [MarketplaceEventReceiver] (`RECEIVER_NOT_EXPORTED`)
 *   to update active tabs instantly on deal, chat, proposal, or sync events.
 * - **Connectivity**: Observes [NetworkStateMonitor] for the offline status banner and reconnect sync.
 */
class MainActivity : BaseActivity() {

    private lateinit var tabContainer: FrameLayout
    private lateinit var offlineBanner: TextView
    private lateinit var bottomNav: View
    private lateinit var pill: View
    private lateinit var navTabs: List<View>
    private lateinit var navIcons: List<ImageView>
    private lateinit var navLabels: List<TextView>

    private var homeTab: HomeTab? = null
    private var marketTab: MarketTab? = null
    private var chatsTab: ChatsTab? = null
    private var profileTab: ProfileTab? = null

    private var activeTab = TAB_HOME
    private var lastTopInset = 0
    private var pillSpring: SpringAnimation? = null
    private var pillInitialized = false

    private var networkMonitor: NetworkStateMonitor? = null
    private var countdownService: MeetupCountdownService? = null
    private var countdownBound = false
    private var countdownJob: Job? = null

    private val eventReceiver = MarketplaceEventReceiver { _, _ ->
        countdownService?.refreshNow()
        refreshActiveTab()
    }

    private val countdownConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as? MeetupCountdownService.LocalBinder)?.service() ?: return
            countdownService = svc
            countdownBound = true
            countdownJob?.cancel()
            countdownJob = lifecycleScope.launch {
                svc.soonestCountdown.collect { countdown ->
                    homeTab?.updateCountdown(countdown)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            countdownBound = false
            countdownService = null
            countdownJob?.cancel()
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Always queried live via NotificationHelper.hasNotificationPermission(this) when needed.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!enforceVerifiedAuthGate(intent)) return

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        tabContainer = findViewById(R.id.tabContainer)
        offlineBanner = findViewById(R.id.offlineBanner)
        bottomNav = findViewById(R.id.bottomNav)
        pill = findViewById(R.id.navIndicator)
        navTabs = listOf(
            findViewById(R.id.navTab0),
            findViewById(R.id.navTab1),
            findViewById(R.id.navTab2),
            findViewById(R.id.navTab3),
        )
        navIcons = listOf(
            findViewById(R.id.navIcon0),
            findViewById(R.id.navIcon1),
            findViewById(R.id.navIcon2),
            findViewById(R.id.navIcon3),
        )
        navLabels = listOf(
            findViewById(R.id.navLabel0),
            findViewById(R.id.navLabel1),
            findViewById(R.id.navLabel2),
            findViewById(R.id.navLabel3),
        )

        networkMonitor = NetworkStateMonitor(this)

        setupInsets()
        setupPillSpring()
        setupBackNavigation()
        setupReactiveObservers()

        val initialTab = intent.getIntExtra(EXTRA_NAV_TAB, TAB_HOME).coerceIn(TAB_HOME, TAB_PROFILE)
        showTab(initialTab, animate = false)
        val forwardedExternal = handleIncomingIntent(intent, animateTab = false)
        if (activeTab == TAB_HOME) homeTab?.ringBellOnceIfUnread()

        scheduleIdlePrewarm()
        MarketplaceSyncService.startIfAllowed(this)
        if (!forwardedExternal) maybePromptNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        if (!UserState.isVerifiedSignedIn) return
        MarketplaceBroadcasts.registerInternalReceiver(
            this,
            eventReceiver,
            MarketplaceBroadcasts.ACTION_STATE_CHANGED,
            MarketplaceBroadcasts.ACTION_DEAL_UPDATED,
            MarketplaceBroadcasts.ACTION_NEW_CHAT_MESSAGE,
            MarketplaceBroadcasts.ACTION_PROPOSAL_UPDATED,
        )
        networkMonitor?.start()
        runCatching {
            bindService(Intent(this, MeetupCountdownService::class.java), countdownConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(eventReceiver) }
        networkMonitor?.stop()
        countdownJob?.cancel()
        if (countdownBound) {
            runCatching { unbindService(countdownConnection) }
            countdownBound = false
            countdownService = null
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!enforceVerifiedAuthGate(intent)) return
        setIntent(intent)
        val target = intent.getIntExtra(EXTRA_NAV_TAB, -1)
        if (target in TAB_HOME..TAB_PROFILE) {
            showTab(target, animate = true)
        }
        handleIncomingIntent(intent, animateTab = true)
    }

    override fun onResume() {
        super.onResume()
        if (!UserState.isVerifiedSignedIn) return
        countdownService?.refreshNow()
        refreshActiveTab()
    }

    override fun onPause() {
        homeTab?.onHide()
        super.onPause()
    }

    override fun onDestroy() {
        pillSpring?.cancel()
        super.onDestroy()
    }

    /**
     * Strict auth gate protecting exported [MainActivity].
     * External deep links, searches, or shares cannot bypass UC student login/verification.
     */
    private fun enforceVerifiedAuthGate(incoming: Intent?): Boolean {
        if (UserState.isVerifiedSignedIn) return true
        val deepLinkStr = if (incoming?.action == Intent.ACTION_VIEW && IntentSecurity.hasSafeDeepLink(incoming.data)) {
            incoming.data?.toString()
        } else {
            null
        }
        val target = when {
            UserState.isSignedIn && UserState.isSuspended -> AccountSuspendedActivity::class.java
            UserState.isSignedIn -> VerifyEmailActivity::class.java
            else -> LoginActivity::class.java
        }
        val redirect = Intent(this, target).apply {
            if (deepLinkStr != null) putExtra(EXTRA_PENDING_DEEP_LINK, deepLinkStr)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(redirect)
        finish()
        return false
    }

    private fun setupReactiveObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppRepository.stateVersion.collect {
                        if (UserState.isSignedIn && UserState.isSuspended) {
                            enforceVerifiedAuthGate(null)
                            return@collect
                        }
                        countdownService?.refreshNow()
                        refreshActiveTab()
                    }
                }
                launch {
                    networkMonitor?.isOnline?.collect { online ->
                        offlineBanner.isVisible = !online
                    }
                }
            }
        }
    }

    private fun maybePromptNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (NotificationHelper.hasNotificationPermission(this)) return
        val prefs = getSharedPreferences("hhts_ui_hints", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_post_notif", false)) return
        prefs.edit().putBoolean("asked_post_notif", true).apply()

        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notif_permission_title)
                .setMessage(R.string.notif_permission_message)
                .setNegativeButton(R.string.btn_not_now, null)
                .setPositiveButton(R.string.btn_allow_notifications) { _, _ ->
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .show()
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Handles all intents declared in AndroidManifest.xml on [MainActivity]:
     * - HTTPS/custom-scheme deep links (`ACTION_VIEW`)
     * - Campus Market search (`ACTION_SEARCH`)
     * - Sanitized incoming shares (`ACTION_SEND` via [IntentSecurity.sanitizeIncomingShare])
     */
    private fun handleIncomingIntent(intent: Intent?, animateTab: Boolean): Boolean {
        if (intent == null) return false

        // Security: Never honor external EXTRA_EMAIL overrides on exported MainActivity.
        intent.removeExtra(EXTRA_EMAIL)

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val route = AppIntents.parseDeepLink(intent.data)
                if (route != null) {
                    intent.data = null
                    dispatchDeepLink(route, animateTab)
                    return true
                }
            }
            Intent.ACTION_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)?.trim().orEmpty().take(100)
                intent.removeExtra(SearchManager.QUERY)
                showTab(TAB_MARKET, animate = animateTab)
                if (query.isNotEmpty()) {
                    ensureMarket().setSearchQuery(query)
                    Toast.makeText(this, getString(R.string.toast_searching_format, query), Toast.LENGTH_SHORT).show()
                }
                return true
            }
            Intent.ACTION_SEND -> {
                val safe = IntentSecurity.sanitizeIncomingShare(this, intent)
                intent.action = null
                if (safe == null) {
                    Toast.makeText(this, R.string.toast_no_app_available, Toast.LENGTH_SHORT).show()
                    return true
                }
                lifecycleScope.launch {
                    val ingestedRef = safe.imageUri?.let { uri ->
                        ImagePipeline.ingestUri(this@MainActivity, uri)
                    }
                    val createIntent = Intent(this@MainActivity, CreateListingActivity::class.java).apply {
                        if (!safe.subject.isNullOrBlank()) putExtra(AppIntents.EXTRA_SHARED_SUBJECT, safe.subject)
                        if (!safe.text.isNullOrBlank()) putExtra(AppIntents.EXTRA_SHARED_TEXT, safe.text)
                        if (!ingestedRef.isNullOrBlank()) putExtra(AppIntents.EXTRA_SHARED_IMAGE_URI, ingestedRef)
                    }
                    startActivity(createIntent)
                }
                return true
            }
        }

        handleRoutingExtras(intent, animateTab)
        return false
    }

    private fun dispatchDeepLink(route: AppIntents.DeepLinkRoute, animateTab: Boolean) {
        when (route) {
            is AppIntents.DeepLinkRoute.ListingDetail -> openListingById(route.listingId)
            is AppIntents.DeepLinkRoute.Market -> {
                showTab(TAB_MARKET, animate = animateTab)
                val market = ensureMarket()
                if (route.program != null) market.selectProgram(route.program)
                if (!route.query.isNullOrBlank()) market.setSearchQuery(route.query)
            }
            AppIntents.DeepLinkRoute.Chats -> showTab(TAB_CHATS, animate = animateTab)
            is AppIntents.DeepLinkRoute.ChatThread -> {
                showTab(TAB_CHATS, animate = animateTab)
                openChatWith(Listings.sellerByName(route.sellerName), route.listingId)
            }
            is AppIntents.DeepLinkRoute.Profile -> {
                showTab(TAB_PROFILE, animate = animateTab)
                ensureProfile().openSubTab(route.subTab, scrollDown = false)
            }
            is AppIntents.DeepLinkRoute.SellerProfile -> {
                if (route.sellerName.isNotBlank()) openSeller(Listings.sellerByName(route.sellerName))
            }
            AppIntents.DeepLinkRoute.CreateListing -> startActivity(Intent(this, CreateListingActivity::class.java))
            AppIntents.DeepLinkRoute.Saved -> startActivity(Intent(this, SavedActivity::class.java))
            AppIntents.DeepLinkRoute.Notifications -> startActivity(Intent(this, NotificationsActivity::class.java))
            AppIntents.DeepLinkRoute.Settings -> startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun handleRoutingExtras(intent: Intent, animateTab: Boolean) {
        if (intent.hasExtra(EXTRA_PROGRAM)) {
            val program = intent.getStringExtra(EXTRA_PROGRAM)
            intent.removeExtra(EXTRA_PROGRAM)
            showTab(TAB_MARKET, animate = animateTab)
            ensureMarket().selectProgram(program)
        }
        if (intent.hasExtra(EXTRA_SEARCH_QUERY)) {
            val query = intent.getStringExtra(EXTRA_SEARCH_QUERY).orEmpty()
            intent.removeExtra(EXTRA_SEARCH_QUERY)
            showTab(TAB_MARKET, animate = animateTab)
            ensureMarket().setSearchQuery(query)
        }
        if (intent.hasExtra(EXTRA_TAB)) {
            val subTab = intent.getIntExtra(EXTRA_TAB, ProfileActivity.TAB_ACTIVE)
            intent.removeExtra(EXTRA_TAB)
            showTab(TAB_PROFILE, animate = animateTab)
            ensureProfile().openSubTab(subTab, scrollDown = true)
        }
    }

    // ---------------------------------------------------------------- edge-to-edge & IME insets

    private fun setupInsets() {
        val root = findViewById<View>(R.id.main)
        val bottomBasePad = bottomNav.paddingBottom
        val rootBaseBottom = root.paddingBottom
        val bannerBaseTop = offlineBanner.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val typing = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > 0

            lastTopInset = bars.top
            offlineBanner.updatePadding(top = bannerBaseTop + bars.top)
            homeTab?.applyTopInset(bars.top)
            marketTab?.applyTopInset(bars.top)
            chatsTab?.applyTopInset(bars.top)
            profileTab?.applyTopInset(bars.top)

            bottomNav.isVisible = !typing
            marketTab?.fab?.isVisible = !typing
            bottomNav.updatePadding(bottom = bottomBasePad + bars.bottom)
            root.updatePadding(bottom = rootBaseBottom + if (typing) ime.bottom else 0)
            insets
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeTab != TAB_HOME) {
                    Motion.tick(bottomNav)
                    showTab(TAB_HOME, animate = true)
                } else {
                    finish()
                }
            }
        })
    }

    // ---------------------------------------------------------------- continuous spring pill

    private fun setupPillSpring() {
        val dp = resources.displayMetrics.density
        pillSpring = SpringAnimation(pill, DynamicAnimation.TRANSLATION_X).apply {
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
            spring = SpringForce(0f).apply {
                stiffness = PILL_STIFFNESS
                dampingRatio = PILL_DAMPING
            }
            addUpdateListener { _, _, velocity ->
                val speed = abs(velocity)
                pill.pivotX = pill.width * 0.5f
                pill.pivotY = pill.height * 0.5f
                pill.scaleX = 1f + (speed / (2900f * dp)).coerceIn(0f, 0.16f)
                pill.scaleY = 1f - (speed / (16000f * dp)).coerceIn(0f, 0.04f)
            }
            addEndListener { _, _, _, _ ->
                pill.scaleX = 1f
                pill.scaleY = 1f
            }
        }

        bottomNav.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (pill.width <= 0 || navTabs[activeTab].width <= 0) return
                bottomNav.viewTreeObserver.removeOnGlobalLayoutListener(this)
                positionPill(activeTab, animate = false)
            }
        })
    }

    private fun pillTargetX(index: Int): Float {
        val slot = navTabs[index]
        return slot.left + (slot.width - pill.width) * 0.5f
    }

    private fun positionPill(index: Int, animate: Boolean) {
        if (pill.width <= 0 || navTabs[index].width <= 0) return
        val targetX = pillTargetX(index)
        if (!animate || !pillInitialized || !Motion.enabled(this)) {
            pillInitialized = true
            pillSpring?.cancel()
            pill.translationX = targetX
            pill.scaleX = 1f
            pill.scaleY = 1f
            return
        }
        val anim = pillSpring ?: return
        if (!anim.isRunning) {
            anim.setStartValue(pill.translationX)
        }
        anim.animateToFinalPosition(targetX)
    }

    // ---------------------------------------------------------------- lazy & idle-warmed tabs

    private fun scheduleIdlePrewarm() {
        val queue = ArrayDeque<() -> Unit>().apply {
            add { ensureMarket() }
            add { ensureChats() }
            add { ensureProfile() }
        }
        Looper.myQueue().addIdleHandler {
            if (isFinishing || isDestroyed) return@addIdleHandler false
            queue.removeFirstOrNull()?.invoke()
            queue.isNotEmpty()
        }
    }

    private fun ensureHome(): HomeTab = homeTab ?: inflateTab(R.layout.tab_home) { root ->
        HomeTab(this, root).also {
            it.applyTopInset(lastTopInset)
            homeTab = it
        }
    }

    private fun ensureMarket(): MarketTab = marketTab ?: inflateTab(R.layout.tab_market) { root ->
        MarketTab(this, root).also {
            it.applyTopInset(lastTopInset)
            marketTab = it
        }
    }

    private fun ensureChats(): ChatsTab = chatsTab ?: inflateTab(R.layout.tab_chats) { root ->
        ChatsTab(this, root).also {
            it.applyTopInset(lastTopInset)
            chatsTab = it
        }
    }

    private fun ensureProfile(): ProfileTab = profileTab ?: inflateTab(R.layout.tab_profile) { root ->
        ProfileTab(this, root).also {
            it.applyTopInset(lastTopInset)
            profileTab = it
        }
    }

    private fun <T> inflateTab(layoutRes: Int, create: (View) -> T): T {
        val view = LayoutInflater.from(this).inflate(layoutRes, tabContainer, false)
        view.visibility = View.INVISIBLE
        tabContainer.addView(view)
        return create(view)
    }

    private fun tabView(index: Int): View = when (index) {
        TAB_HOME -> ensureHome().root
        TAB_MARKET -> ensureMarket().root
        TAB_CHATS -> ensureChats().root
        else -> ensureProfile().root
    }

    internal fun activeTabIndex(): Int = activeTab

    private fun refreshActiveTab() {
        when (activeTab) {
            TAB_HOME -> homeTab?.onShow()
            TAB_MARKET -> marketTab?.refresh()
            TAB_CHATS -> chatsTab?.refresh()
            TAB_PROFILE -> profileTab?.onShow()
        }
    }

    // ---------------------------------------------------------------- smooth in-place tab switch

    private fun showTab(next: Int, animate: Boolean) {
        val previous = activeTab
        val switching = animate && previous != next
        val outgoingView = if (switching) tabView(previous) else null
        val incomingView = tabView(next)

        if (switching) {
            when (previous) {
                TAB_HOME -> homeTab?.onHide()
                TAB_MARKET -> marketTab?.onHide()
                TAB_CHATS -> chatsTab?.onHide()
            }
        }

        activeTab = next
        updateNavVisuals(previous, next, animate = switching)
        positionPill(next, animate = switching)

        listOf(homeTab?.root, marketTab?.root, chatsTab?.root, profileTab?.root).forEach { v ->
            if (v != null && v !== incomingView && v !== outgoingView) {
                v.animate().cancel()
                v.visibility = View.INVISIBLE
                v.alpha = 1f
                v.translationX = 0f
            }
        }

        if (!switching || !Motion.enabled(this)) {
            outgoingView?.apply {
                animate().cancel()
                visibility = View.INVISIBLE
                alpha = 1f
                translationX = 0f
            }
            incomingView.apply {
                animate().cancel()
                visibility = View.VISIBLE
                alpha = 1f
                translationX = 0f
                bringToFront()
            }
            refreshActiveTab()
            return
        }

        val dp = resources.displayMetrics.density
        val direction = if (next > previous) 1 else -1

        outgoingView?.let { out ->
            out.animate().cancel()
            out.animate()
                .alpha(0f)
                .translationX(-direction * 10f * dp)
                .setDuration(140)
                .setStartDelay(0)
                .setInterpolator(Motion.EASE_OUT)
                .withLayer()
                .withEndAction {
                    if (activeTab != previous) {
                        out.visibility = View.INVISIBLE
                        out.alpha = 1f
                        out.translationX = 0f
                    }
                }
                .start()
        }

        incomingView.animate().cancel()
        incomingView.visibility = View.VISIBLE
        incomingView.bringToFront()
        incomingView.alpha = 0f
        incomingView.translationX = direction * 12f * dp
        incomingView.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(180)
            .setStartDelay(0)
            .setInterpolator(Motion.EMPHASIZED)
            .withLayer()
            .start()

        refreshActiveTab()
    }

    private fun updateNavVisuals(previous: Int, next: Int, animate: Boolean) {
        val activeColor = getColor(R.color.white)
        val inactiveColor = getColor(R.color.text_on_green_dim)
        navIcons.forEachIndexed { index, icon ->
            val isOn = index == next
            val changed = animate && (index == next || index == previous)
            Motion.iconTint(icon, if (isOn) inactiveColor else activeColor, if (isOn) activeColor else inactiveColor, changed)
            Motion.textColor(navLabels[index], if (isOn) activeColor else inactiveColor, changed)
            if (changed && isOn && Motion.enabled(this)) {
                Motion.cancelAllSprings(icon)
                icon.scaleX = 0.84f
                icon.scaleY = 0.84f
                Motion.springScale(icon, 1f, Motion.Spatial.EXPRESSIVE)
            }
        }
    }

    // ---------------------------------------------------------------- XML onClick handlers

    override fun openHome(view: View) = tapTab(TAB_HOME)
    override fun openMarket(view: View) = tapTab(TAB_MARKET)
    override fun openChats(view: View) = tapTab(TAB_CHATS)
    override fun openProfile(view: View) = tapTab(TAB_PROFILE)

    private fun tapTab(index: Int) {
        if (activeTab == index) return
        Motion.tick(navTabs[index])
        showTab(index, animate = true)
    }

    override fun openProgram(view: View) {
        Motion.tick(view)
        showTab(TAB_MARKET, animate = true)
        ensureMarket().selectProgram(view.tag as? String)
    }

    fun openNotifications(view: View) {
        Motion.tick(view)
        startActivity(Intent(this, NotificationsActivity::class.java))
    }

    fun openExchanged(view: View) {
        Motion.tick(view)
        showTab(TAB_PROFILE, animate = true)
        ensureProfile().openSubTab(ProfileActivity.TAB_COMPLETED, scrollDown = true)
    }

    fun openActiveListings(view: View) {
        Motion.tick(view)
        showTab(TAB_PROFILE, animate = true)
        ensureProfile().openSubTab(ProfileActivity.TAB_ACTIVE, scrollDown = true)
    }

    fun openSort(view: View) = ensureMarket().openSort(view)
    fun clearSearch(view: View) = ensureMarket().clearSearch(view)
    fun clearAllFilters(view: View) = ensureMarket().clearAllFilters(view)
    fun openFilters(view: View) = ensureMarket().openFilters(view)

    fun toggleSearch(view: View) = ensureChats().toggleSearch(view)
    fun openThread(view: View) = ensureChats().openThread(view)

    fun openMenu(view: View) = ensureProfile().openMenu(view)

    companion object {
        const val TAB_HOME = 0
        const val TAB_MARKET = 1
        const val TAB_CHATS = 2
        const val TAB_PROFILE = 3
        const val EXTRA_NAV_TAB = "nav_tab"

        private const val PILL_STIFFNESS = 600f
        private const val PILL_DAMPING = 0.74f
    }
}
