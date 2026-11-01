package com.droiddeck.launcher

import android.app.Activity
import android.os.Handler
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.UpdatesActions
import com.droiddeck.launcher.ui.UpdatesState
import com.droiddeck.launcher.update.AppUpdateService
import com.droiddeck.launcher.update.AppUpdateState
import com.droiddeck.launcher.update.AppUpdateState.Phase
import com.droiddeck.launcher.update.AppUpdates
import com.droiddeck.launcher.update.SelfInstaller
import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.core.FileUtils

internal class UpdatesMenu(private val activity: Activity, private val ui: Handler) {
    var catalog by mutableStateOf<AppUpdates.Catalog?>(null)
    var follow by mutableStateOf(AppUpdates.Follow(AppUpdates.Channel.STABLE))
    var checking by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var stage by mutableStateOf<String?>(null)
    var percent by mutableIntStateOf(-1)
    var askPermission by mutableStateOf(false)
    var sources by mutableStateOf<List<RepositorySources.Source>>(emptyList())
    private var pending: AppUpdates.Release? = null
    private var lastCheck = 0L
    private var lastRateLimitPopup = 0L
    private var request = 0
    private var resumed = false
    private var ready by mutableStateOf(false)
    private var taskApk by mutableStateOf<AppUpdates.Apk?>(null)
    private var taskLabel by mutableStateOf<String?>(null)
    private var taskError: String? = null
    private var continuing = false
    private var continueReady = false
    private val observer: (AppUpdateState.Snapshot) -> Unit = { task ->
        taskError = task.error
        error = task.error
        ready = task.phase == Phase.READY
        taskApk = if (task.phase in setOf(Phase.IDLE, Phase.FAILED)) null else task.apk
        taskLabel = task.targetLabel
        percent = task.percent
        stage = when (task.phase) {
            Phase.DOWNLOADING -> activity.getString(R.string.upd_stage_downloading)
            Phase.VERIFYING -> activity.getString(R.string.upd_verifying)
            Phase.INSTALLING -> activity.getString(R.string.upd_stage_installing)
            Phase.CONFIRMING -> activity.getString(R.string.upd_stage_confirm)
            else -> null
        }
        continueWhenVisible()
    }

    fun state() = UpdatesState(catalog, follow, checking, error, stage, percent, askPermission, ready, taskApk, taskLabel, sources, follow.sourceId.ifEmpty { null })

    fun actions() = UpdatesActions(
        onCheck = { check(force = true) },
        onFollow = ::choose,
        onInstall = ::install,
        onContinueInstall = { continueWhenVisible(explicit = true) },
        onDiscardDownload = {
            continueReady = false
            askPermission = false
            pending = null
            AppUpdateState.discardReady(activity)
        },
        onAllowInstalls = {
            askPermission = false
            runCatching { activity.startActivity(SelfInstaller.permissionIntent(activity)) }
                .onFailure { error = activity.getString(R.string.upd_unknown_apps_failed); pending = null }
        },
        onDismissPermission = { askPermission = false; pending = null; continueReady = false },
        onSourcesChanged = ::sourcesChanged,
        onSource = ::chooseSource,
    )

    val hasUpdate: Boolean get() = AppUpdates.hasUpdate(catalog, follow)

    fun onPause() { resumed = false }

    fun onResume() {
        resumed = true
        continueWhenVisible(explicit = continueReady)
        pending?.let { r ->
            if (SelfInstaller.canInstall(activity)) { pending = null; install(r) }
        }
        if (!SessionState.running) check(force = false)
    }

    fun check(force: Boolean) {
        if (checking || stage != null) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60_000) return
        lastCheck = now
        val id = ++request
        val selectedSource = follow.sourceId
        checking = true
        Thread({
            val result = runCatching { AppUpdates.refresh(activity, selectedSource) }
            ui.post {
                if (id != request) return@post
                checking = false
                result.onSuccess {
                    catalog = it
                    follow = AppUpdates.follow(activity, it).let { selected ->
                        if (selected.sourceId.isEmpty()) selected.copy(sourceId = selectedSource) else selected
                    }
                    error = taskError
                }.onFailure {
                    Log.w("UpdatesMenu", "update source refresh", it)
                    val rateLimited = GitHubArtifacts.isRateLimited(it)
                    if (force || rateLimited) {
                        error = GitHubArtifacts.failureMessage(activity, it).let { message ->
                            if (rateLimited) message else activity.getString(R.string.upd_couldnt_check, message)
                        }
                        val popupAt = System.currentTimeMillis()
                        if (rateLimited && (force || popupAt - lastRateLimitPopup >= 3 * 60_000)) {
                            lastRateLimitPopup = popupAt
                            android.widget.Toast.makeText(activity, error, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }, "app-updates").start()
    }

    private fun choose(f: AppUpdates.Follow) {
        continueReady = false
        askPermission = false
        pending = null
        AppUpdates.setFollow(activity, f)
        val selected = f.copy(sourceId = follow.sourceId)
        AppUpdates.setFollow(activity, selected)
        follow = selected
        error = taskError
    }

    private fun sourcesChanged() {
        request++
        checking = false
        pending = null
        askPermission = false
        sources = AppUpdates.sources(activity).filterNot { it.hidden }
        if (sources.none { it.id == follow.sourceId }) {
            catalog = null
            error = null
        } else {
            catalog = AppUpdates.cached(activity, follow.sourceId)
            error = null
            check(force = true)
        }
    }

    private fun chooseSource(sourceId: String) {
        if (stage != null || sources.none { it.id == sourceId } || sourceId == follow.sourceId) return
        request++
        checking = false
        pending = null
        askPermission = false
        follow = follow.copy(sourceId = sourceId)
        AppUpdates.setFollow(activity, follow)
        catalog = AppUpdates.cached(activity, sourceId)
        error = null
        check(force = true)
    }

    private fun install(r: AppUpdates.Release) {
        if (stage != null) return
        val selectedCatalog = catalog
        if (selectedCatalog == null || selectedCatalog.sourceId != follow.sourceId ||
            sources.none { it.id == follow.sourceId } || AppUpdates.release(selectedCatalog, follow) !== r) {
            error = activity.getString(R.string.upd_no_download)
            return
        }
        val apk = r.apk ?: run { error = activity.getString(R.string.upd_no_download); return }
        AppUpdates.installBlock(r)?.let { error = it.message(activity); return }
        if (SessionState.running) { error = activity.getString(R.string.upd_stop_first); return }
        if (!SelfInstaller.canInstall(activity)) { pending = r; askPermission = true; return }
        error = null
        val sourceId = follow.sourceId
        val sourceGeneration = RepositorySources.generation(activity, AppUpdates.SOURCE_SCOPE, AppUpdates.sourceBuiltIns())
        if (!RepositorySources.isCurrent(activity, AppUpdates.SOURCE_SCOPE, AppUpdates.sourceBuiltIns(), sourceId, sourceGeneration)) {
            error = activity.getString(R.string.upd_no_download)
            return
        }
        val target = "${r.version ?: r.title.ifBlank { r.tag }} (${r.commit.take(7)})"
        AppUpdateService.start(activity, apk, target)
    }

    private fun continueWhenVisible(explicit: Boolean = false) {
        if (!resumed || continuing || SessionState.running) return
        val power = activity.getSystemService(android.os.PowerManager::class.java)
        val keyguard = activity.getSystemService(android.app.KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) return
        continuing = true
        try {
            val task = AppUpdateState.snapshot
            val selected = catalog?.let { AppUpdates.release(it, follow)?.apk }
            val matchesSelection = selected != null && selected.sha256.equals(task.apk?.sha256, ignoreCase = true)
            if (task.phase == Phase.READY && (explicit || task.automatic && matchesSelection)) {
                if (!SelfInstaller.canInstall(activity)) {
                    continueReady = true
                    askPermission = true
                    return
                }
                continueReady = false
                AppUpdateState.install(activity)
            }
            AppUpdateState.takeConfirmation()?.let { confirm ->
                if (runCatching { activity.startActivity(confirm) }.isFailure) {
                    AppUpdateState.fail(activity, activity.getString(R.string.upd_not_offered))
                }
            }
        } finally {
            continuing = false
        }
    }

    fun start() {
        sources = AppUpdates.sources(activity).filterNot { it.hidden }
        follow = AppUpdates.follow(activity, null)
        catalog = AppUpdates.cached(activity, follow.sourceId)
        follow = AppUpdates.follow(activity, catalog)
        AppUpdateState.initialize(activity)
        AppUpdateState.subscribe(observer)
    }

    fun unregister() {
        resumed = false
        request++
        AppUpdateState.unsubscribe(observer)
    }
}
