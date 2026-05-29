package com.bearinmind.equalizer314

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.audio.SessionEffectManager
import com.bearinmind.equalizer314.state.EqPreferencesManager
import com.bearinmind.equalizer314.ui.PresetDropdownAdapter
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Detail screen of the "Channel Input" pipeline card. Lists installed
 * apps so the user can assign saved EQ presets per app. When an app
 * later broadcasts `OPEN_AUDIO_EFFECT_CONTROL_SESSION`,
 * [com.bearinmind.equalizer314.audio.SessionEffectManager] looks up
 * the package's binding and applies the bound preset to its session.
 */
class ChannelInputActivity : AppCompatActivity() {

    private lateinit var eqPrefs: EqPreferencesManager
    private lateinit var appsList: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var appsAdapter: AppsAdapter

    // "Now playing" panel — always visible. Shows both broadcast-attached
    // and NLS-detected sessions.
    private lateinit var currentSessionSection: LinearLayout
    private lateinit var currentSessionList: RecyclerView
    private lateinit var currentSessionEmpty: TextView
    private lateinit var sessionsAdapter: ActiveSessionsAdapter

    // Collapsible "Apps" section — mirrors the Devices section
    // pattern from AudioOutputActivity. Header is clickable; body is
    // visibility-toggled with an AutoTransition for the open/close
    // animation. Expanded state persists in this activity's local
    // SharedPreferences.
    private lateinit var appsHeader: LinearLayout
    private lateinit var appsBody: LinearLayout
    private lateinit var appsChevron: TextView
    private var appsExpanded = true

    // Persistent "Session detection" toggle card. Always visible —
    // the switch reflects the current system Notification access
    // state, and tapping it routes the user to system Settings (the
    // only place Android lets a third-party app flip the listener
    // bind). The body text below the title varies with the state.
    private lateinit var enableDetectionCard: MaterialCardView
    private lateinit var enableDetectionTitle: TextView
    private lateinit var enableDetectionBody: TextView
    private lateinit var enableDetectionSwitch: MaterialSwitch

    // "Skip system sounds" toggle — gates EqService's bypass of the
    // global DP on notification / ringtone / alarm / call streams.
    // Default ON in prefs; tap to flip and the change is applied
    // immediately via EqService.ACTION_APPLY_BYPASS_PREF.
    private lateinit var bypassSystemSoundsCard: MaterialCardView
    private lateinit var bypassSystemSoundsSwitch: MaterialSwitch

    // Bound EqService so we can read the live set of attached sessions
    // (SessionEffectManager.getActiveSessions). Null when the service
    // isn't running — Session-based mode shows the empty card in that
    // case, which is the right thing because no sessions are attached.
    private var eqService: EqService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            eqService = (binder as? EqService.EqBinder)?.service
            refreshCurrentSessions()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            eqService = null
            refreshCurrentSessions()
        }
    }

    private val sessionsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshCurrentSessions()
        }
    }

    // Cycling-dots animation for the "Loading apps…" placeholder.
    // Tick every 400ms: "Loading apps ." → ". ." → ". . ." → loop.
    private val loadingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var loadingFrame = 0
    private val loadingRunnable = object : Runnable {
        override fun run() {
            val dots = when (loadingFrame % 4) {
                0 -> ""
                1 -> " ."
                2 -> " . ."
                else -> " . . ."
            }
            emptyState.text = "Loading apps$dots"
            loadingFrame++
            loadingHandler.postDelayed(this, 400)
        }
    }

    private fun startLoadingAnimation() {
        loadingFrame = 0
        emptyState.visibility = View.VISIBLE
        loadingHandler.removeCallbacks(loadingRunnable)
        loadingHandler.post(loadingRunnable)
    }

    private fun stopLoadingAnimation() {
        loadingHandler.removeCallbacks(loadingRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_input)

        eqPrefs = EqPreferencesManager(this)

        findViewById<ImageButton>(R.id.channelInputBackButton).setOnClickListener { finish() }
        appsList = findViewById(R.id.appsList)
        emptyState = findViewById(R.id.appsEmptyState)

        appsAdapter = AppsAdapter()
        appsList.layoutManager = LinearLayoutManager(this)
        appsList.adapter = appsAdapter
        appsList.isNestedScrollingEnabled = false

        currentSessionSection = findViewById(R.id.currentSessionSection)
        currentSessionList = findViewById(R.id.currentSessionList)
        currentSessionEmpty = findViewById(R.id.currentSessionEmpty)
        sessionsAdapter = ActiveSessionsAdapter()
        currentSessionList.layoutManager = LinearLayoutManager(this)
        currentSessionList.adapter = sessionsAdapter
        currentSessionList.isNestedScrollingEnabled = false

        // Collapsible Apps section — header click animates the body
        // open/closed via AutoTransition (same look as AudioOutput's
        // Devices section). Expanded state persists per-screen.
        appsHeader = findViewById(R.id.appsHeader)
        appsBody = findViewById(R.id.appsBody)
        appsChevron = findViewById(R.id.appsChevron)
        appsExpanded = getPreferences(MODE_PRIVATE).getBoolean(PREF_APPS_EXPANDED, true)
        applyAppsExpanded(animate = false)
        appsHeader.setOnClickListener {
            appsExpanded = !appsExpanded
            getPreferences(MODE_PRIVATE)
                .edit().putBoolean(PREF_APPS_EXPANDED, appsExpanded).apply()
            applyAppsExpanded(animate = true)
        }

        enableDetectionCard = findViewById(R.id.enableDetectionCard)
        enableDetectionTitle = findViewById(R.id.enableDetectionTitle)
        enableDetectionBody = findViewById(R.id.enableDetectionBody)
        enableDetectionSwitch = findViewById(R.id.enableDetectionSwitch)
        // OnClickListener (not OnCheckedChange) so programmatic isChecked
        // updates don't recurse. The user tap will flip the switch
        // visually for an instant; we snap it back to the real NLS
        // state, then route to system Settings — the only place Android
        // lets us flip BIND_NOTIFICATION_LISTENER_SERVICE. On return,
        // onResume re-syncs from the system.
        enableDetectionSwitch.setOnClickListener {
            enableDetectionSwitch.isChecked = isNotificationListenerGranted()
            try {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Throwable) {
                Toast.makeText(this, "Could not open Notification access settings", Toast.LENGTH_SHORT).show()
            }
        }
        // Tapping the card body (anywhere outside the switch) also goes
        // to Settings — feels right because the entire card is the
        // affordance.
        enableDetectionCard.setOnClickListener { enableDetectionSwitch.performClick() }

        // "Skip system sounds" toggle — safety default (on) protects
        // against the 127-band FFT pre-EQ + limiter distorting short
        // transient streams like notifications. Flipping it fires
        // ACTION_APPLY_BYPASS_PREF so the change takes effect now,
        // not on the next playback-config callback.
        bypassSystemSoundsCard = findViewById(R.id.bypassSystemSoundsCard)
        bypassSystemSoundsSwitch = findViewById(R.id.bypassSystemSoundsSwitch)
        bypassSystemSoundsSwitch.isChecked = eqPrefs.getBypassSystemSounds()
        val toggleBypass = {
            val next = !eqPrefs.getBypassSystemSounds()
            eqPrefs.setBypassSystemSounds(next)
            bypassSystemSoundsSwitch.isChecked = next
            val intent = Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_APPLY_BYPASS_PREF)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Throwable) {
                // Service might not be running — that's fine, the pref
                // is saved and will be picked up the next time EQ starts.
            }
        }
        bypassSystemSoundsSwitch.setOnClickListener { toggleBypass() }
        bypassSystemSoundsCard.setOnClickListener { toggleBypass() }

        setupRoutingModeChips()
        setupAppsFilterChips()
        loadApps()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(SessionEffectManager.ACTION_SESSIONS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionsChangedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sessionsChangedReceiver, filter)
        }
        // Bind (do not start) — if the service isn't running, the
        // panel just shows its empty state.
        bindService(
            Intent(this, EqService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        refreshDetectionCtaVisibility()
        refreshCurrentSessions()
    }

    override fun onResume() {
        super.onResume()
        // User may have toggled the listener in Settings while we were
        // in the background — re-check on every return.
        refreshDetectionCtaVisibility()
    }

    /** Smooth, Compose-style expand/collapse for the Apps section.
     *  Matches the AppDrawer launcher's [`AnimatedVisibility +
     *  expandVertically/shrinkVertically`] feel using a [ValueAnimator]
     *  on the body's `layoutParams.height` — no Transition fade, no
     *  visibility blink. 300 ms symmetric, FastOutSlowInInterpolator
     *  (Compose's default). Chevron rotates in lockstep. */
    private fun applyAppsExpanded(animate: Boolean) {
        val targetRotation = if (appsExpanded) 90f else 0f
        if (!animate) {
            appsBody.visibility = if (appsExpanded) View.VISIBLE else View.GONE
            if (appsExpanded) restoreWrapContentHeight(appsBody)
            appsChevron.rotation = targetRotation
            return
        }
        animateCollapse(appsBody, appsExpanded)
        appsChevron.animate()
            .rotation(targetRotation)
            .setDuration(EXPAND_DURATION_MS)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .start()
    }

    /** Animates [body]'s height between 0 and its measured natural
     *  height. Used by every collapsible section on this screen so the
     *  feel stays consistent. After [expand]ing, height is restored to
     *  WRAP_CONTENT so the section adapts when its contents change. */
    private fun animateCollapse(body: View, expand: Boolean) {
        val interp = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
        if (expand) {
            body.visibility = View.VISIBLE
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                (body.parent as View).width, View.MeasureSpec.EXACTLY,
            )
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            body.measure(widthSpec, heightSpec)
            val target = body.measuredHeight
            android.animation.ValueAnimator.ofInt(0, target).apply {
                duration = EXPAND_DURATION_MS
                interpolator = interp
                addUpdateListener {
                    body.layoutParams.height = it.animatedValue as Int
                    body.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        restoreWrapContentHeight(body)
                    }
                })
                start()
            }
        } else {
            val start = body.height
            android.animation.ValueAnimator.ofInt(start, 0).apply {
                duration = EXPAND_DURATION_MS
                interpolator = interp
                addUpdateListener {
                    body.layoutParams.height = it.animatedValue as Int
                    body.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        body.visibility = View.GONE
                        restoreWrapContentHeight(body)
                    }
                })
                start()
            }
        }
    }

    private fun restoreWrapContentHeight(v: View) {
        v.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        v.requestLayout()
    }

    private fun isNotificationListenerGranted(): Boolean =
        NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)

    /** Card stays visible in both states. Title and body are fixed;
     *  only the switch reflects the current system Notification access
     *  state. */
    private fun refreshDetectionCtaVisibility() {
        val granted = isNotificationListenerGranted()
        enableDetectionCard.visibility = View.VISIBLE
        enableDetectionSwitch.isChecked = granted
        // "Dump services" = the reflected
        // ServiceManager.getService("audio").dumpAsync path that
        // recovers session IDs from audioserver. (When the OEM denies
        // the dump call, MediaSessionManager is the public-API
        // fallback — same user-facing wording covers both since the
        // user-visible result is the same: "lists apps playing audio".)
        enableDetectionBody.text = "Lists apps playing audio using dumpsys"
    }

    override fun onStop() {
        try { unregisterReceiver(sessionsChangedReceiver) } catch (_: Throwable) {}
        try { unbindService(serviceConnection) } catch (_: Throwable) {}
        eqService = null
        super.onStop()
    }

    private fun refreshCurrentSessions() {
        val sessions = eqService?.sessionEffects?.getActiveSessions().orEmpty()
        if (sessions.isEmpty()) {
            currentSessionList.visibility = View.GONE
            currentSessionEmpty.visibility = View.VISIBLE
            sessionsAdapter.setItems(emptyList())
        } else {
            currentSessionEmpty.visibility = View.GONE
            currentSessionList.visibility = View.VISIBLE
            // Resolve icon + label on the fly — there will usually be
            // only one or two active sessions at a time, so the cost
            // is negligible and avoids holding a stale cache.
            val pm = packageManager
            // Coalesce by package — apps like Nyx Music Player open
            // two AudioTracks at once for gapless playback and produce
            // two SessionEffectManager entries. The user binds by
            // package, so showing two rows for the same app is just
            // noise. Pick the most-informative session per package:
            //   - BROADCAST source beats DETECTED (authoritative)
            //   - real positive sessionId beats synthetic negative
            //   - isPlaying = OR of all sessions in the group
            val rows = sessions
                .groupBy { it.packageName }
                .map { (pkg, group) ->
                    val rep = group.sortedWith(
                        compareByDescending<SessionEffectManager.ActiveSession> {
                            it.source == SessionEffectManager.AttachSource.BROADCAST
                        }.thenByDescending { it.sessionId > 0 }
                    ).first()
                    val (icon, label) = runCatching {
                        val info = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationIcon(info) to pm.getApplicationLabel(info).toString()
                    }.getOrElse { null to pkg }
                    SessionRow(
                        sessionId = rep.sessionId,
                        packageName = pkg,
                        label = label,
                        icon = icon,
                        presetName = rep.presetName,
                        source = rep.source,
                        isPlaying = group.any { it.isPlaying },
                    )
                }
                .sortedBy { it.label.lowercase() }
            sessionsAdapter.setItems(rows)
        }
    }

    private fun setupRoutingModeChips() {
        val chipGroup = findViewById<ChipGroup>(R.id.routingModeChips)
        val global = findViewById<Chip>(R.id.routingModeGlobal)
        val perApp = findViewById<Chip>(R.id.routingModePerApp)
        // Map: 0 = System-wide, 1 = Session-based.
        // Any legacy "2" (was Both in the previous 3-mode setup) is
        // treated as System-wide on read so existing installs migrate.
        val mode = eqPrefs.getAudioRoutingMode()
        when (mode) {
            1 -> perApp.isChecked = true
            else -> global.isChecked = true
        }
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newMode = if (id == R.id.routingModePerApp) 1 else 0
            eqPrefs.saveAudioRoutingMode(newMode)
            // Tell the service to apply the new mode — if Session-
            // based was just selected, the service stops the global
            // DP so we don't end up double-EQing bound apps.
            val serviceIntent = android.content.Intent(this, com.bearinmind.equalizer314.audio.EqService::class.java)
                .setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_APPLY_ROUTING_MODE)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (_: Throwable) { /* service may already be torn down */ }
        }
    }

    private fun setupAppsFilterChips() {
        val chipGroup = findViewById<ChipGroup>(R.id.appsFilterChips)
        val filtered = findViewById<Chip>(R.id.appsFilterFiltered)
        val showAll = findViewById<Chip>(R.id.appsFilterShowAll)
        when (eqPrefs.getAppListFilterMode()) {
            1 -> showAll.isChecked = true
            else -> filtered.isChecked = true
        }
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newMode = if (id == R.id.appsFilterShowAll) 1 else 0
            if (newMode == eqPrefs.getAppListFilterMode()) return@setOnCheckedStateChangeListener
            eqPrefs.saveAppListFilterMode(newMode)
            loadApps()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun loadApps() {
        // PackageManager.getApplicationLabel + getApplicationIcon are
        // synchronous IPC calls that on a phone with 100+ apps add up
        // to several hundred milliseconds — enough to noticeably lag
        // the activity opening. Push the enumeration onto IO, then
        // hand the result to the adapter on Main.
        startLoadingAnimation()
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val pm = packageManager

                // Two modes, controlled by appsFilterChips:
                //   FILTERED (default) — same heuristic Wavelet / Poweramp
                //   EQ use: include an app only if it matches ANY of:
                //     (a) declares a MEDIA_BUTTON broadcast receiver
                //         (Spotify, Poweramp, AIMP, …)
                //     (b) declares a MediaBrowserService (Android Auto
                //         media-app contract)
                //     (c) handles audio/* MIME types (file-manager-style
                //         music players)
                //     (d) we've already seen it broadcast a session
                //   plus every app the user has already bound a preset to
                //   (kept regardless of the heuristic).
                //   SHOW_ALL — every installed app, alphabetical. Useful
                //   for games and other apps that declare none of the
                //   above but still produce audio (eFootball, GTA SA, etc).
                val showAll = eqPrefs.getAppListFilterMode() == 1
                val mediaCandidates: Set<String>? = if (showAll) null else {
                    val mediaButtonApps = pm.queryBroadcastReceivers(
                        Intent(Intent.ACTION_MEDIA_BUTTON), 0,
                    ).map { it.activityInfo.packageName }.toHashSet()

                    val mediaBrowserApps = pm.queryIntentServices(
                        Intent("android.media.browse.MediaBrowserService"), 0,
                    ).map { it.serviceInfo.packageName }.toHashSet()

                    val audioMimeApps = pm.queryIntentActivities(
                        Intent(Intent.ACTION_VIEW).apply { type = "audio/*" }, 0,
                    ).map { it.activityInfo.packageName }.toHashSet()

                    val seen = eqPrefs.getAllSeenApps().toHashSet()
                    val bindings = eqPrefs.getAllAppBindings().associateBy { it.packageName }

                    mediaButtonApps + mediaBrowserApps + audioMimeApps + seen +
                        bindings.keys  // always show bound apps even if filters drop them
                }

                // In Show All mode, restrict to packages that declare a
                // launcher activity (ACTION_MAIN + CATEGORY_LAUNCHER) —
                // i.e. apps the user would actually see in their app
                // drawer. Without this filter, getInstalledApplications
                // returns every package with an <application> tag,
                // including system providers (com.android.providers.*),
                // ad / privacy services (com.google.android.adservices.api),
                // wallpaper services, and OEM background daemons that
                // aren't user-facing apps. Keep current bindings in the
                // list regardless so a stale binding to a non-launchable
                // package is still removable from this screen.
                val launchablePackages: Set<String>? = if (showAll) {
                    val launchable = pm.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
                    ).map { it.activityInfo.packageName }.toHashSet()
                    launchable + eqPrefs.getAllAppBindings().map { it.packageName }
                } else null

                pm.getInstalledApplications(0)
                    .filter { mediaCandidates == null || it.packageName in mediaCandidates }
                    .filter { launchablePackages == null || it.packageName in launchablePackages }
                    .filter { it.packageName != packageName }
                    .map { info ->
                        AppRow(
                            packageName = info.packageName,
                            label = pm.getApplicationLabel(info).toString(),
                            icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                        )
                    }
                    // Pure alphabetical order — no "bring bound/seen
                    // apps to the top." The Now playing panel above
                    // already surfaces what's currently active, so
                    // the Apps list stays predictable and scannable.
                    .sortedBy { it.label.lowercase() }
            }

            stopLoadingAnimation()
            if (rows.isEmpty()) {
                emptyState.text = "No apps detected yet."
                emptyState.visibility = View.VISIBLE
                appsAdapter.setItems(emptyList())
            } else {
                emptyState.visibility = View.GONE
                appsAdapter.setItems(rows)
            }
        }
    }

    override fun onDestroy() {
        stopLoadingAnimation()
        super.onDestroy()
    }

    private data class AppRow(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
    )

    private data class SessionRow(
        val sessionId: Int,
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val presetName: String?,
        val source: SessionEffectManager.AttachSource,
        val isPlaying: Boolean,
    )

    private inner class ActiveSessionsAdapter : RecyclerView.Adapter<ActiveSessionsAdapter.VH>() {
        private val items = mutableListOf<SessionRow>()

        fun setItems(newItems: List<SessionRow>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.sessionRowIcon)
            val name: TextView = view.findViewById(R.id.sessionRowName)
            val meta: TextView = view.findViewById(R.id.sessionRowMeta)
            val pulse: ImageView = view.findViewById(R.id.sessionRowPulse)
            val presetLayout: TextInputLayout = view.findViewById(R.id.sessionRowPresetLayout)
            val dropdown: MaterialAutoCompleteTextView = view.findViewById(R.id.sessionRowPresetDropdown)
        }

        // Shared with AppsAdapter via the lastDismissAt timestamp on
        // that adapter; we use our own here since the two lists never
        // share a row.
        private var lastDismissAt = 0L

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_active_session_row, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            holder.icon.setImageDrawable(r.icon)
            holder.name.text = r.label
            // Meta is just the package now — the source-tag /
            // session-id are implementation detail, and the preset
            // dropdown below already communicates binding state.
            holder.meta.text = r.packageName

            // Speaker pulse: animated green when actively outputting,
            // static dim cone when present-but-silent. Tint is driven
            // off the ImageView so the AnimationDrawable's per-frame
            // tint metadata stays consistent across the loop.
            val pulse = holder.pulse.drawable as? android.graphics.drawable.AnimationDrawable
            if (pulse != null) {
                val tintColor = androidx.core.content.ContextCompat.getColor(
                    this@ChannelInputActivity,
                    if (r.isPlaying) R.color.pulse_active_green
                    else R.color.pulse_inactive_red,
                )
                holder.pulse.imageTintList =
                    android.content.res.ColorStateList.valueOf(tintColor)
                if (r.isPlaying) {
                    if (!pulse.isRunning) pulse.start()
                } else {
                    pulse.stop()
                    pulse.selectDrawable(0)
                }
            }

            // Same dropdown behavior as the Apps list — pick a preset
            // here and the binding applies to this package immediately.
            bindPresetDropdown(
                presetLayout = holder.presetLayout,
                dropdown = holder.dropdown,
                packageName = r.packageName,
                appLabel = r.label,
                getLastDismiss = { lastDismissAt },
                setLastDismiss = { lastDismissAt = it },
            )
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppsAdapter.VH>() {

        private val items = mutableListOf<AppRow>()
        private var lastDismissAt = 0L  // shared anti-reopen timestamp

        fun setItems(newItems: List<AppRow>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appRowIcon)
            val name: TextView = view.findViewById(R.id.appRowName)
            val pkg: TextView = view.findViewById(R.id.appRowPackage)
            val presetLayout: TextInputLayout = view.findViewById(R.id.appRowPresetLayout)
            val dropdown: MaterialAutoCompleteTextView = view.findViewById(R.id.appRowPresetDropdown)
            val card: MaterialCardView = view as MaterialCardView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_row, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            holder.icon.setImageDrawable(row.icon)
            holder.name.text = row.label
            holder.pkg.text = row.packageName
            bindPresetDropdown(
                presetLayout = holder.presetLayout,
                dropdown = holder.dropdown,
                packageName = row.packageName,
                appLabel = row.label,
                getLastDismiss = { lastDismissAt },
                setLastDismiss = { lastDismissAt = it },
            )
        }
    }

    /** Wire up a preset-binding dropdown for a row. Shared between
     *  AppsAdapter and ActiveSessionsAdapter — both let the user pick
     *  a custom preset to associate with a package. Selecting "(none)"
     *  removes the binding; selecting a missing-preset row is a no-op. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindPresetDropdown(
        presetLayout: TextInputLayout,
        dropdown: MaterialAutoCompleteTextView,
        packageName: String,
        appLabel: String,
        getLastDismiss: () -> Long,
        setLastDismiss: (Long) -> Unit,
    ) {
        val knownNames = listCustomPresetNames()
        val binding = eqPrefs.getAppBinding(packageName)
        val currentSelection = binding?.presetName ?: "(none)"
        val missing = binding != null && binding.presetName !in knownNames
        val entries = buildPresetEntries(if (missing) binding!!.presetName else null)

        dropdown.setText(
            if (missing) "${binding!!.presetName} (missing)" else currentSelection,
            false,
        )
        dropdown.setAdapter(PresetDropdownAdapter(this, entries))

        dropdown.setOnDismissListener { setLastDismiss(System.currentTimeMillis()) }
        presetLayout.setOnClickListener {
            if (System.currentTimeMillis() - getLastDismiss() < 300) {
                setLastDismiss(0L)
                return@setOnClickListener
            }
            if (dropdown.isPopupShowing) dropdown.dismissDropDown() else dropdown.showDropDown()
        }
        applyBoxOutlineRipple(presetLayout, dropdown)

        dropdown.setOnItemClickListener { _, _, pos, _ ->
            val pick = entries[pos].displayName
            when {
                pick == "(none)" -> {
                    eqPrefs.removeAppBinding(packageName)
                    notifyAppBindingChanged(packageName)
                    Toast.makeText(this, "Unbound $appLabel", Toast.LENGTH_SHORT).show()
                }
                pick.endsWith(" (missing)") -> { /* dangling */ }
                else -> {
                    eqPrefs.saveAppBinding(EqPreferencesManager.AppBinding(packageName, pick))
                    notifyAppBindingChanged(packageName)
                    Toast.makeText(this, "Bound \"$pick\" to $appLabel", Toast.LENGTH_SHORT).show()
                }
            }
            dropdown.clearFocus()
        }
    }

    /** Tell EqService to rebuild any per-session DP belonging to
     *  [audioAppPackage] so the binding edit the user just made takes
     *  effect on the live audio without requiring the user to stop
     *  and restart the audio app. Mirrors AudioOutputActivity's
     *  notifyBindingChanged path for per-app sessions. */
    private fun notifyAppBindingChanged(audioAppPackage: String) {
        sendBroadcast(
            Intent(com.bearinmind.equalizer314.audio.EqService.ACTION_REAPPLY_APP_BINDING)
                .setPackage(this.packageName)
                .putExtra(
                    com.bearinmind.equalizer314.audio.EqService.EXTRA_APP_PACKAGE,
                    audioAppPackage,
                )
        )
    }

    // ---- Helpers (mirrored from AudioOutputActivity) -------------------

    private fun listCustomPresetNames(): List<String> {
        val prefs = getSharedPreferences("custom_presets", MODE_PRIVATE)
        return prefs.all
            .filter { (k, v) -> k.startsWith("preset_") && v is String }
            .keys
            .map { it.removePrefix("preset_") }
            .sorted()
    }

    private fun loadPresetJson(name: String): JSONObject? {
        val prefs = getSharedPreferences("custom_presets", MODE_PRIVATE)
        val str = runCatching { prefs.getString("preset_$name", null) }
            .getOrNull() ?: return null
        return runCatching { JSONObject(str) }.getOrNull()
    }

    private fun buildPresetEntries(missingPresetName: String?): List<PresetDropdownAdapter.Entry> {
        val out = mutableListOf<PresetDropdownAdapter.Entry>()
        out.add(PresetDropdownAdapter.Entry("(none)", null))
        for (name in listCustomPresetNames()) {
            out.add(PresetDropdownAdapter.Entry(name, loadPresetJson(name)))
        }
        if (missingPresetName != null) {
            out.add(PresetDropdownAdapter.Entry("$missingPresetName (missing)", null))
        }
        return out
    }

    /** Same pattern as AudioOutputActivity — applies a runtime-sized
     *  ripple foreground to the TextInputLayout so the dropdown box's
     *  ripple stays inside the outline rectangle exactly. */
    private fun applyBoxOutlineRipple(layout: TextInputLayout, dropdown: android.view.View) {
        layout.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (dropdown.width <= 0 || dropdown.height <= 0 || layout.width <= 0) return true
                layout.viewTreeObserver.removeOnPreDrawListener(this)

                val rect = android.graphics.Rect(0, 0, dropdown.width, dropdown.height)
                layout.offsetDescendantRectToMyCoords(dropdown, rect)
                val cornerRadius = layout.boxCornerRadiusTopStart
                val highlightColor = MaterialColors.getColor(
                    layout,
                    com.google.android.material.R.attr.colorControlHighlight,
                )
                val mask = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    this.cornerRadius = cornerRadius
                    setColor(android.graphics.Color.WHITE)
                }
                val ripple = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(highlightColor),
                    null,
                    mask,
                )
                layout.foreground = android.graphics.drawable.InsetDrawable(
                    ripple,
                    rect.left,
                    rect.top,
                    (layout.width - rect.right).coerceAtLeast(0),
                    (layout.height - rect.bottom).coerceAtLeast(0),
                )
                return true
            }
        })
    }

    companion object {
        private const val PREF_APPS_EXPANDED = "appsExpanded"
        /** Symmetric duration for the Apps section open/close. Bumped
         *  past Compose's 300 ms `AnimatedVisibility` default toward
         *  Material's "Emphasized" timing (≈500 ms) so the slide reads
         *  as a deliberate motion rather than a quick reveal. */
        private const val EXPAND_DURATION_MS = 500L
    }
}
