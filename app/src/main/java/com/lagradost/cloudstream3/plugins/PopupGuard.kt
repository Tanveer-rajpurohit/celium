package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.LayoutInflaterCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Android adapter that maps classes and stack frames to a [FrameOrigin] (Celium 6, Requirement 2).
 * Every failure falls back to [FrameOrigin.App] (fail open).
 */
object PluginOrigin {
    private const val TAG = "PopupGuard"

    private val loaders = ConcurrentHashMap<ClassLoader, String>()
    private val cache = ConcurrentHashMap<String, FrameOrigin>()
    private val appLoader: ClassLoader? = PluginOrigin::class.java.classLoader

    private val frameworkPrefixes = arrayOf(
        "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "dalvik.",
        "com.android.", "com.google.android.material.", "sun.", "libcore.",
    )

    private const val PKG = "com.lagradost.cloudstream3"

    /** App helpers that attribution skips, including their nested/lambda classes. */
    private val transparentWithNested = arrayOf(
        "$PKG.plugins.PopupGuard",
        "$PKG.plugins.PluginOrigin",
        "$PKG.plugins.PopupInflaterFactory",
        "$PKG.plugins.PopupAttribution",
        "$PKG.plugins.PopupPolicy",
        "$PKG.CommonActivity",
        "$PKG.utils.SnackbarHelper",
    )

    /** Classes whose gate overrides are transparent; their lambdas stay App. */
    private val transparentExact = setOf(
        "$PKG.MainActivity",
        "$PKG.CloudStreamApp",
    )

    fun register(loader: ClassLoader, internalName: String) {
        loaders[loader] = internalName
        cache.clear()
    }

    fun unregister(loader: ClassLoader) {
        loaders.remove(loader)
        cache.clear()
    }

    val hasPlugins: Boolean get() = loaders.isNotEmpty()

    /** Origin of a class name: App, Framework, or Plugin(internalName). Cached per class name. */
    fun classify(className: String): FrameOrigin {
        cache[className]?.let { return it }
        val origin = try {
            computeOrigin(className)
        } catch (t: Throwable) {
            FrameOrigin.App
        }
        cache[className] = origin
        return origin
    }

    private fun isFrameworkName(className: String): Boolean {
        if (frameworkPrefixes.any { className.startsWith(it) }) return true
        if (className in transparentExact) return true
        return transparentWithNested.any { className == it || className.startsWith("$it$") }
    }

    private fun computeOrigin(className: String): FrameOrigin {
        if (isFrameworkName(className)) return FrameOrigin.Framework
        if (loaders.isEmpty()) return FrameOrigin.App
        val inApp = try {
            Class.forName(className, false, appLoader)
            true
        } catch (_: Throwable) {
            false
        }
        if (inApp) return FrameOrigin.App
        for ((loader, name) in loaders) {
            val owned = try {
                loader.loadClass(className).classLoader === loader
            } catch (_: Throwable) {
                false
            }
            if (owned) return FrameOrigin.Plugin(name)
        }
        return FrameOrigin.App
    }

    /** Origin of a class by class loader identity. */
    fun ofClass(cls: Class<*>): FrameOrigin = try {
        val loader = cls.classLoader
        val plugin = if (loader != null) loaders[loader] else null
        when {
            plugin != null -> FrameOrigin.Plugin(plugin)
            isFrameworkName(cls.name) -> FrameOrigin.Framework
            else -> FrameOrigin.App
        }
    } catch (t: Throwable) {
        FrameOrigin.App
    }

    /** Origin of the current call: the nearest non-framework frame decides. */
    fun current(): FrameOrigin {
        if (!hasPlugins) return FrameOrigin.App
        return try {
            PopupAttribution.attribute(Throwable().stackTrace.map { it.className }, ::classify)
        } catch (t: Throwable) {
            Log.w(TAG, "Attribution failed, showing popup", t)
            FrameOrigin.App
        }
    }
}

/**
 * Gate used by every popup entry point a plugin can reach (Requirement 2.1, 2.2, 2.4, 2.6).
 */
object PopupGuard {
    private const val TAG = "PopupGuard"

    /** How long a plugin UI opened under [userAction] is treated as still attached. */
    private const val UI_ATTACHED_MS = 30L * 60L * 1000L

    private val allowances = ConcurrentHashMap<String, Long>()
    private val scopeDepth = ConcurrentHashMap<String, Int>()

    @Volatile
    private var lastInputMs: Long = Long.MIN_VALUE / 2

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Call before super.onCreate: installs the inflater factory and fragment callbacks. */
    fun install(activity: FragmentActivity) {
        try {
            val inflater = activity.layoutInflater
            if (inflater.factory2 == null && inflater.factory == null) {
                LayoutInflaterCompat.setFactory2(inflater, PopupInflaterFactory(activity))
            } else {
                Log.w(TAG, "Inflater factory already set, dialog gate not installed")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to install inflater factory", t)
        }
        try {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                PluginDialogFragmentCallbacks, true
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to install fragment callbacks", t)
        }
    }

    /** Pure decision for [origin] with the current allowances. Never logs. */
    internal fun decide(origin: FrameOrigin): Decision = PopupPolicy.decide(
        origin, allowances, lastInputMs, SystemClock.uptimeMillis()
    )

    internal fun logSuppressed(type: PopupType, plugin: String) {
        Log.i(TAG, "Suppressed ${type.label()} from plugin $plugin")
    }

    internal fun logFailure(plugin: String, t: Throwable) {
        Log.e(TAG, "Failed to intercept popup from plugin $plugin, keeping it hidden", t)
    }

    /** True when the popup must be discarded. Logs once per suppression. */
    fun shouldBlock(type: PopupType, origin: FrameOrigin = PluginOrigin.current()): Boolean {
        val plugin = (origin as? FrameOrigin.Plugin)?.name ?: return false
        return try {
            when (val decision = decide(origin)) {
                is Decision.Suppress -> {
                    logSuppressed(type, decision.plugin)
                    true
                }
                Decision.Show -> false
            }
        } catch (t: Throwable) {
            // Origin is a plugin: keep the popup hidden (Requirement 2.6).
            logFailure(plugin, t)
            true
        }
    }

    /** Runs [block] with popups from [pluginName] allowed (plugin settings entry points). */
    fun <T> userAction(pluginName: String, block: () -> T): T {
        scopeDepth.compute(pluginName) { _, d -> (d ?: 0) + 1 }
        allowances[pluginName] = PopupPolicy.USER_ACTION_SCOPE
        // The user just acted: popups from the opened UI may follow right after.
        lastInputMs = SystemClock.uptimeMillis()
        try {
            return block()
        } finally {
            val left = scopeDepth.compute(pluginName) { _, d ->
                val n = (d ?: 1) - 1
                if (n <= 0) null else n
            }
            if (left == null) {
                allowances[pluginName] = SystemClock.uptimeMillis() + UI_ATTACHED_MS
            }
        }
    }

    /** Called from MainActivity.dispatchTouchEvent / dispatchKeyEvent. */
    fun onUserInput(uptimeMs: Long) {
        lastInputMs = uptimeMs
    }

    internal fun post(action: () -> Unit) {
        mainHandler.post {
            try {
                action()
            } catch (t: Throwable) {
                Log.e(TAG, "Popup removal failed", t)
            }
        }
    }

    private fun PopupType.label(): String = when (this) {
        PopupType.Dialog -> "dialog"
        PopupType.Toast -> "toast"
        PopupType.Snackbar -> "snackbar"
        PopupType.ExternalLink -> "external link"
    }
}

/** Stops plugin DialogFragments from creating a dialog and removes them. */
private object PluginDialogFragmentCallbacks : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentPreAttached(fm: FragmentManager, f: Fragment, context: Context) {
        if (f !is DialogFragment || !PluginOrigin.hasPlugins) return
        val origin = PluginOrigin.ofClass(f.javaClass)
        val plugin = (origin as? FrameOrigin.Plugin)?.name ?: return
        try {
            val decision = PopupGuard.decide(origin)
            if (decision !is Decision.Suppress) return
            suppress(f)
            PopupGuard.logSuppressed(PopupType.Dialog, decision.plugin)
        } catch (t: Throwable) {
            PopupGuard.logFailure(plugin, t)
            try {
                suppress(f)
            } catch (_: Throwable) {
            }
        }
    }

    private fun suppress(f: DialogFragment) {
        f.showsDialog = false
        PopupGuard.post {
            if (f.isAdded && !f.isRemoving) {
                f.parentFragmentManager.beginTransaction().remove(f).commitAllowingStateLoss()
            }
        }
    }
}

/**
 * Inflater factory that hides dialogs and removes snackbars inflated by plugin code.
 * Creation is delegated to AppCompat so normal inflation is unchanged.
 */
class PopupInflaterFactory(activity: FragmentActivity) : LayoutInflater.Factory2 {
    private val activityRef = WeakReference(activity)
    private val contextOrigins = WeakHashMap<Context, FrameOrigin>()
    private val handledRoots: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
        onCreateView(null, name, context, attrs)

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        val created = delegateCreate(parent, name, context, attrs)
        if (!PluginOrigin.hasPlugins) return created

        val isSnackbar = name.contains("Snackbar")
        val origin = try {
            originFor(context, isSnackbar) ?: return created
        } catch (t: Throwable) {
            return created // fail open
        }
        val plugin = (origin as? FrameOrigin.Plugin)?.name ?: return created
        val type = if (isSnackbar) PopupType.Snackbar else PopupType.Dialog

        return try {
            val decision = PopupGuard.decide(origin)
            if (decision !is Decision.Suppress) return created
            val view = created ?: createFallback(name, context, attrs)
            val target = view ?: parent ?: return null
            hideOnAttach(target, type, decision.plugin)
            view
        } catch (t: Throwable) {
            PopupGuard.logFailure(plugin, t)
            created
        }
    }

    /** Attribution runs once per new context, and for every snackbar view. */
    private fun originFor(context: Context, isSnackbar: Boolean): FrameOrigin? {
        if (isSnackbar) return PluginOrigin.current()
        synchronized(contextOrigins) {
            if (contextOrigins.containsKey(context)) return contextOrigins[context]
        }
        val origin = PluginOrigin.current()
        synchronized(contextOrigins) { contextOrigins[context] = origin }
        return origin
    }

    private fun delegateCreate(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? = try {
        (activityRef.get() as? AppCompatActivity)?.delegate?.createView(parent, name, context, attrs)
    } catch (t: Throwable) {
        null
    }

    /** Creates platform views that AppCompat leaves to the inflater, so a listener can be attached. */
    private fun createFallback(name: String, context: Context, attrs: AttributeSet): View? {
        if (name == "view" || name == "merge" || name == "include" || name == "fragment" || name == "blink") {
            return null
        }
        val inflater = LayoutInflater.from(context)
        if (name.contains('.')) {
            return try {
                inflater.createView(name, null, attrs)
            } catch (_: Throwable) {
                null
            }
        }
        for (prefix in arrayOf("android.widget.", "android.view.", "android.webkit.")) {
            try {
                return inflater.createView(name, prefix, attrs)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun hideOnAttach(view: View, type: PopupType, plugin: String) {
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                try {
                    if (type == PopupType.Snackbar) hideSnackbar(v, plugin)
                    else hideDialogRoot(v, plugin)
                } catch (t: Throwable) {
                    PopupGuard.logFailure(plugin, t)
                }
            }

            override fun onViewDetachedFromWindow(v: View) {}
        }
        if (view.isAttachedToWindow) listener.onViewAttachedToWindow(view)
        else view.addOnAttachStateChangeListener(listener)
    }

    private fun hideDialogRoot(view: View, plugin: String) {
        val root = view.rootView ?: return
        // Never hide the activity window itself (plugin views inflated into Celium screens).
        val activityDecor = activityRef.get()?.window?.peekDecorView()
        if (root === activityDecor) return
        if (!handledRoots.add(root)) return
        PopupGuard.logSuppressed(PopupType.Dialog, plugin)
        root.visibility = View.GONE
        onPreDrawOnce(root) {
            root.visibility = View.GONE
        }
    }

    private fun hideSnackbar(view: View, plugin: String) {
        // Outermost Snackbar-named ancestor is the snackbar layout added to the activity.
        var target = view
        var p = view.parent
        while (p is View) {
            if (p.javaClass.name.contains("Snackbar")) target = p
            p = p.parent
        }
        if (!handledRoots.add(target)) return
        PopupGuard.logSuppressed(PopupType.Snackbar, plugin)
        target.visibility = View.GONE
        onPreDrawOnce(target) {
            (target.parent as? ViewGroup)?.removeView(target)
        }
    }

    /** Runs [action] before the next draw and cancels that frame. */
    private fun onPreDrawOnce(view: View, action: () -> Unit) {
        val observer = view.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val vto = view.viewTreeObserver
                if (vto.isAlive) vto.removeOnPreDrawListener(this)
                try {
                    action()
                } catch (t: Throwable) {
                    Log.e("PopupGuard", "Popup hide failed", t)
                }
                return false
            }
        })
    }
}
