package com.hmx.pdf.spell

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextServicesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Android's installed spell-check session (`SpellCheckerSession`), which is a local text service
 * bound over Binder — this class itself never opens a network connection and never uploads text.
 *
 * Offline policy: before using a service, its package is inspected. A service whose package
 * requests `INTERNET` is refused, because the platform service could forward what it is given;
 * the panel then reports "unavailable". This is a deliberately conservative proxy, not a proof:
 * services can ship other networking code without that permission. The alternative — bundling a
 * dictionary — is deferred until its licence/size/memory analysis is documented (roadmap 4.3).
 *
 * `newSpellCheckerSession` needs a Looper, so construct this on the main thread. Requests also
 * run there and are bounded by [TIMEOUT_MS] so a wedged service cannot hold the scan forever.
 */
class AndroidSpellEngine(context: Context) : SpellEngine,
    SpellCheckerSession.SpellCheckerSessionListener {

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
    private var session: SpellCheckerSession? = null
    private var pending: Pending? = null

    override var available: Boolean = false
        private set
    override var language: String? = null
        private set

    /** Why the service is unavailable, for diagnostics (null when available). */
    var reason: String? = null
        private set

    private class Pending(
        val words: List<String>,
        val result: HashMap<String, List<String>>
    ) {
        var continuation: kotlinx.coroutines.CancellableContinuation<Map<String, List<String>>>? = null
    }

    init {
        open()
    }

    private fun open() {
        val m = manager ?: run { reason = "text services unavailable"; return }
        val locale = Locale.getDefault()
        val pkg = servicePackage()
        if (pkg == null) {
            reason = "no spell service"
            return
        }
        if (requestsInternet(pkg)) {
            reason = "service is network-capable"
            return
        }
        session = runCatching { m.newSpellCheckerSession(null, locale, this, true) }.getOrNull()
        available = session != null
        if (available) language = locale.toLanguageTag() else reason = "session unavailable"
    }

    /**
     * The package of the service the system would use. API 31 exposes it directly; older releases
     * only publish the chosen component in the secure "spell_checker" setting (the same value the
     * platform's text-services manager reads), so that is parsed there.
     */
    private fun servicePackage(): String? =
        if (Build.VERSION.SDK_INT >= 31) currentInfo31()?.packageName
        else legacyPackage()

    @androidx.annotation.RequiresApi(31)
    private fun currentInfo31(): SpellCheckerInfo? = manager?.currentSpellCheckerInfo

    private fun legacyPackage(): String? {
        val component = runCatching {
            android.provider.Settings.Secure.getString(app.contentResolver, "spell_checker")
        }.getOrNull()
        return component?.substringBefore('/')?.takeIf { it.isNotBlank() }
    }

    /** Conservative offline proxy: a package that can reach the network is not used. */
    private fun requestsInternet(pkg: String): Boolean = try {
        val info = app.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) ?: false
    } catch (_: Exception) {
        // Unknown package state: refuse rather than guess.
        true
    }

    override suspend fun suggest(words: List<String>): Map<String, List<String>> {
        if (!available || words.isEmpty()) return emptyMap()
        return withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(TIMEOUT_MS) { request(words) } ?: run {
                cancelPending()
                emptyMap()
            }
        }
    }

    private suspend fun request(words: List<String>): Map<String, List<String>> {
        val s = session ?: return emptyMap()
        return suspendCancellableCoroutine { cont ->
            val p = Pending(words, HashMap())
            p.continuation = cont
            pending = p
            cont.invokeOnCancellation {
                runCatching { s.cancel() }
                if (pending === p) pending = null
            }
            runCatching {
                s.getSuggestions(
                    words.map { android.view.textservice.TextInfo(it) }.toTypedArray(),
                    MAX_SUGGESTIONS,
                    false
                )
            }.onFailure {
                pending = null
                if (cont.isActive) cont.resume(emptyMap())
            }
        }
    }

    private fun cancelPending() {
        runCatching { session?.cancel() }
        pending = null
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        val p = pending ?: return
        pending = null
        if (results != null) {
            // One result per requested word; a service may answer fewer, hence the index guard.
            for (i in results.indices) {
                val info = results[i] ?: continue
                val word = p.words.getOrNull(i) ?: continue
                val list = ArrayList<String>(info.suggestionsCount)
                for (k in 0 until info.suggestionsCount) list.add(info.getSuggestionAt(k))
                p.result[word] = list
            }
        }
        val cont = p.continuation
        if (cont != null && cont.isActive) cont.resume(p.result)
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        // Sentence mode is not requested; word mode keeps per-token ranges exact.
    }

    override fun close() {
        cancelPending()
        runCatching { session?.close() }
        session = null
        available = false
    }

    private companion object {
        const val MAX_SUGGESTIONS = 5
        const val TIMEOUT_MS = 5_000L
    }
}
