package com.nevergrowold.holdhightheshop

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

/**
 * Single-host shell for the four primary tabs (Home, Market, Chats, Profile).
 *
 * Also serves as the exported Manifest intent router for:
 * - Deep links (`ACTION_VIEW` for `https://holdhightheshop.app/...` and `holdhightheshop://...`)
 * - Campus Market search (`ACTION_SEARCH`)
 * - Incoming text/photo shares (`ACTION_SEND`) forwarded to [CreateListingActivity]
 */
class MainActivity : BaseActivity() {

    private lateinit var tabContainer: FrameLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        tabContainer = findViewById(R.id.tabContainer)
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

        setupInsets()
        setupPillSpring()
        setupBackNavigation()

        val initialTab = intent.getIntExtra(EXTRA_NAV_TAB, TAB_HOME).coerceIn(TAB_HOME, TAB_PROFILE)
        showTab(initialTab, animate = false)
        handleIncomingIntent(intent, animateTab = false)
        if (activeTab == TAB_HOME) homeTab?.ringBellOnceIfUnread()

        // Quietly pre-warm the remaining three tabs after frame 1 paints so even
        // the very first tap on Market, Chats or Profile responds in 0ms.
        scheduleIdlePrewarm()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = intent.getIntExtra(EXTRA_NAV_TAB, -1)
        if (target in TAB_HOME..TAB_PROFILE) {
            showTab(target, animate = true)
        }
        handleIncomingIntent(intent, animateTab = true)
    }

    override fun onResume() {
        super.onResume()
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
     * Handles all intents declared in AndroidManifest.xml on [MainActivity]:
     * deep links (`ACTION_VIEW`), system search (`ACTION_SEARCH`), incoming
     * shares (`ACTION_SEND`), and explicit internal routing extras.
     */
    private fun handleIncomingIntent(intent: Intent?, animateTab: Boolean) {
        if (intent == null) return

        intent.getStringExtra(EXTRA_EMAIL)?.takeIf { it.isNotBlank() }?.let { email ->
            UserState.updateEmail(this, email)
            intent.removeExtra(EXTRA_EMAIL)
        }

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val route = AppIntents.parseDeepLink(intent.data)
                if (route != null) {
                    intent.data = null
                    dispatchDeepLink(route, animateTab)
                    return
                }
            }
            Intent.ACTION_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
                intent.removeExtra(SearchManager.QUERY)
                showTab(TAB_MARKET, animate = animateTab)
                if (query.isNotEmpty()) {
                    ensureMarket().setSearchQuery(query)
                    Toast.makeText(this, getString(R.string.toast_searching_format, query), Toast.LENGTH_SHORT).show()
                }
                return
            }
            Intent.ACTION_SEND -> {
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val streamUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val createIntent = Intent(this, CreateListingActivity::class.java).apply {
                    if (!subject.isNullOrBlank()) putExtra(AppIntents.EXTRA_SHARED_SUBJECT, subject)
                    if (!sharedText.isNullOrBlank()) putExtra(AppIntents.EXTRA_SHARED_TEXT, sharedText)
                    if (streamUri != null) {
                        putExtra(AppIntents.EXTRA_SHARED_IMAGE_URI, streamUri.toString())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                intent.action = null
                startActivity(createIntent)
                return
            }
        }

        handleRoutingExtras(intent, animateTab)
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
            is AppIntents.DeepLinkRoute.SellerProfile -> openSeller(Listings.sellerByName(route.sellerName))
            is AppIntents.DeepLinkRoute.MapDirections -> startActivity(
                Intent(this, MapActivity::class.java).putExtra(MapActivity.EXTRA_PLACE, route.place)
            )
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

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val typing = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > 0

            lastTopInset = bars.top
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
        // Listeners are attached ONCE here before start() — retargeting mid-flight
        // via animateToFinalPosition() preserves velocity smoothly and never throws.
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
        // INVISIBLE (not GONE) pre-measures & pre-lays out idle-warmed tabs so
        // switching tabs triggers zero measure/layout passes.
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

        // Hide any other stale tab views left mid-transition from rapid tapping.
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

        // Outgoing & incoming cross-transition simultaneously inside the same GPU window
        // (zero Activity IPC, zero relayout, zero white flash, zero buffering).
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

    // ---------------------------------------------------------------- tab switches & XML onClick handlers

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

    /** Home: bell icon opens notifications. */
    fun openNotifications(view: View) {
        Motion.tick(view)
        startActivity(Intent(this, NotificationsActivity::class.java))
    }

    /** Home: "Exchanged" stat card opens Profile on the Completed tab. */
    fun openExchanged(view: View) {
        Motion.tick(view)
        showTab(TAB_PROFILE, animate = true)
        ensureProfile().openSubTab(ProfileActivity.TAB_COMPLETED, scrollDown = true)
    }

    /** Home: "Active listings" stat card opens Profile on the Active tab. */
    fun openActiveListings(view: View) {
        Motion.tick(view)
        showTab(TAB_PROFILE, animate = true)
        ensureProfile().openSubTab(ProfileActivity.TAB_ACTIVE, scrollDown = true)
    }

    /** Market handlers forwarded from tab_market.xml. */
    fun openSort(view: View) = ensureMarket().openSort(view)
    fun clearSearch(view: View) = ensureMarket().clearSearch(view)
    fun clearAllFilters(view: View) = ensureMarket().clearAllFilters(view)
    fun openFilters(view: View) = ensureMarket().openFilters(view)

    /** Chats handlers forwarded from tab_chats.xml. */
    fun toggleSearch(view: View) = ensureChats().toggleSearch(view)
    fun openThread(view: View) = ensureChats().openThread(view)

    /** Profile handlers forwarded from tab_profile.xml. */
    fun openMenu(view: View) = ensureProfile().openMenu(view)

    companion object {
        const val TAB_HOME = 0
        const val TAB_MARKET = 1
        const val TAB_CHATS = 2
        const val TAB_PROFILE = 3
        const val EXTRA_NAV_TAB = "nav_tab"

        /** Responsive, sleek, visibly springy pill physics (point-to-point glide). */
        private const val PILL_STIFFNESS = 600f
        private const val PILL_DAMPING = 0.74f
    }
}
