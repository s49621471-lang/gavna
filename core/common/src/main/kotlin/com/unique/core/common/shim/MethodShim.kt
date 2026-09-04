package com.unique.core.common.shim

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Declarative, signature-agnostic interception of framework interfaces.
 *
 * The problem this solves: every previous generation of Android app-virtualization
 * engines patched system-service calls by *argument index* —
 * `if (name == "startActivity") args[1] = hostPackage`. Framework AIDL signatures change
 * in almost every release, so those engines break on each new Android version and
 * accumulate `if (SDK_INT >= …)` branches until nobody can reason about them.
 *
 * Here, a shim declares *what* to rewrite and never *where*. Argument positions are
 * discovered at install time from the interface actually present on the device, so a new
 * argument inserted by a future release changes nothing. A method with no matching rule
 * passes through untouched, which is the safe default: an interface UNIQUE does not
 * understand behaves exactly as the platform intended.
 *
 * This is pure JVM code with no Android dependency precisely so the mechanism can be
 * tested against synthetic interfaces that mimic a signature change.
 */
class MethodShim internal constructor(
    val methodName: String,
    private val minApi: Int,
    private val maxApi: Int,
    private val rules: List<ArgRule>,
    private val resultRewriter: ((Any?) -> Any?)?,
    private val replacement: ((ShimCall) -> Any?)?,
) {
    fun appliesToApi(api: Int): Boolean = api in minApi..maxApi

    /**
     * Binds this shim to a concrete [method], resolving each rule to argument positions.
     * Returns null when nothing in the method matches — the caller then installs no plan
     * and the method is invoked untouched.
     */
    internal fun bind(method: Method): BoundShim? {
        if (method.name != methodName) return null
        val types = method.parameterTypes
        val plans = ArrayList<BoundRule>(rules.size)
        for (rule in rules) {
            val positions = rule.resolve(types)
            if (positions.isNotEmpty()) plans += BoundRule(rule, positions)
        }
        if (plans.isEmpty() && resultRewriter == null && replacement == null) return null
        return BoundShim(this, plans, resultRewriter, replacement)
    }
}

/** A shim bound to one concrete method, with argument positions already resolved. */
internal class BoundShim(
    val shim: MethodShim,
    private val rules: List<BoundRule>,
    private val resultRewriter: ((Any?) -> Any?)?,
    private val replacement: ((ShimCall) -> Any?)?,
) {
    fun apply(call: ShimCall, invoke: (Array<Any?>) -> Any?): Any? {
        for (bound in rules) bound.apply(call.args)
        if (replacement != null) return replacement.invoke(call)
        val result = invoke(call.args)
        return resultRewriter?.invoke(result) ?: result
    }
}

internal class BoundRule(private val rule: ArgRule, private val positions: IntArray) {
    fun apply(args: Array<Any?>) {
        for (i in positions) {
            if (i >= args.size) continue
            args[i] = rule.transform(args[i]) ?: args[i]
        }
    }
}

/**
 * The call being intercepted, handed to a full replacement handler.
 *
 * [proceed] exists because most interesting interception is *conditional*: a virtual
 * PackageManager answers for the virtual package and must hand every other package to the
 * real one. Without it, a replacement handler has to choose between answering everything
 * or nothing, and the usual workaround - reaching around the shim to call the target
 * directly - loses the argument rewriting the shim already applied.
 */
class ShimCall(
    val method: Method,
    val args: Array<Any?>,
    val target: Any?,
    private val original: (Array<Any?>) -> Any?,
) {
    /** Invokes the real implementation with the current (possibly rewritten) arguments. */
    fun proceed(): Any? = original(args)

    /** The first argument of type [T], or null. Convenience for signature-agnostic handlers. */
    inline fun <reified T : Any> firstArgOf(): T? = args.filterIsInstance<T>().firstOrNull()
}

/**
 * One rewrite rule.
 *
 * [type] is what makes position discovery possible: rules are matched against the
 * method's declared parameter types, not against indices.
 */
class ArgRule internal constructor(
    private val type: Class<*>,
    private val scope: Scope,
    private val predicate: (Any?) -> Boolean,
    private val transform: (Any?) -> Any?,
) {
    enum class Scope { ALL, FIRST, LAST }

    internal fun resolve(paramTypes: Array<Class<*>>): IntArray {
        val matches = paramTypes.indices.filter { type.isAssignableFrom(boxed(paramTypes[it])) }
        return when (scope) {
            Scope.ALL -> matches.toIntArray()
            Scope.FIRST -> matches.take(1).toIntArray()
            Scope.LAST -> matches.takeLast(1).toIntArray()
        }
    }

    internal fun transform(value: Any?): Any? =
        if (predicate(value)) transform.invoke(value) else null

    private fun boxed(c: Class<*>): Class<*> = when (c) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> c
    }
}

/** Builder DSL. See [shim]. */
class ShimBuilder(private val methodName: String) {
    var minApi: Int = 1
    var maxApi: Int = Int.MAX_VALUE

    private val rules = ArrayList<ArgRule>()
    private var resultRewriter: ((Any?) -> Any?)? = null
    private var replacement: ((ShimCall) -> Any?)? = null

    /** Rewrites every argument of type [T] that satisfies [matching]. */
    inline fun <reified T : Any> rewriteAll(
        noinline matching: (T) -> Boolean = { true },
        noinline with: (T) -> T?,
    ) = addRule(T::class.java, ArgRule.Scope.ALL, matching, with)

    /** Rewrites the first argument of type [T] that satisfies [matching]. */
    inline fun <reified T : Any> rewriteFirst(
        noinline matching: (T) -> Boolean = { true },
        noinline with: (T) -> T?,
    ) = addRule(T::class.java, ArgRule.Scope.FIRST, matching, with)

    /** Rewrites the last argument of type [T] that satisfies [matching]. */
    inline fun <reified T : Any> rewriteLast(
        noinline matching: (T) -> Boolean = { true },
        noinline with: (T) -> T?,
    ) = addRule(T::class.java, ArgRule.Scope.LAST, matching, with)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> addRule(
        type: Class<T>,
        scope: ArgRule.Scope,
        matching: (T) -> Boolean,
        with: (T) -> T?,
    ) {
        rules += ArgRule(
            type = type,
            scope = scope,
            predicate = { v -> v != null && type.isInstance(v) && matching(v as T) },
            transform = { v -> with(v as T) },
        )
    }

    /** Rewrites the returned value. */
    fun rewriteResult(block: (Any?) -> Any?) { resultRewriter = block }

    /** Replaces the call entirely; the real method is never invoked. */
    fun replaceWith(block: (ShimCall) -> Any?) { replacement = block }

    internal fun build() = MethodShim(methodName, minApi, maxApi, rules, resultRewriter, replacement)
}

/** Declares a shim for every method named [methodName] on an interface. */
fun shim(methodName: String, block: ShimBuilder.() -> Unit): MethodShim =
    ShimBuilder(methodName).apply(block).build()

/** Reported by [ShimRegistry.install] so a failed binding is visible, not silent. */
data class ShimBindResult(
    val bound: List<String>,
    val unbound: List<String>,
) {
    val allBound: Boolean get() = unbound.isEmpty()
}

/**
 * Holds the shims for one interface and produces the proxy that applies them.
 */
class ShimRegistry(private val apiLevel: Int) {

    private val shims = ArrayList<MethodShim>()

    fun register(vararg s: MethodShim): ShimRegistry {
        s.filter { it.appliesToApi(apiLevel) }.forEach(shims::add)
        return this
    }

    /**
     * Wraps [target] in a proxy implementing [interfaces], applying every registered shim.
     *
     * Binding happens once, here — not per call — so the hot path is a hash lookup and a
     * short loop over already-resolved positions.
     */
    fun <T : Any> wrap(target: T, vararg interfaces: Class<*>): Pair<T, ShimBindResult> {
        val plans = HashMap<Method, BoundShim>()
        val boundNames = LinkedHashSet<String>()

        for (iface in interfaces) {
            for (method in iface.methods) {
                for (s in shims) {
                    val bound = s.bind(method) ?: continue
                    plans[method] = bound
                    boundNames += s.methodName
                    break // first shim that binds wins; order of registration is the priority
                }
            }
        }
        val unbound = shims.map { it.methodName }.distinct().filter { it !in boundNames }

        val handler = InvocationHandler { _, method, rawArgs ->
            val args: Array<Any?> = rawArgs?.copyOf() ?: emptyArray()
            val plan = plans[method]
            if (plan == null) {
                invokeTarget(target, method, args)
            } else {
                val invoke: (Array<Any?>) -> Any? = { a -> invokeTarget(target, method, a) }
                plan.apply(ShimCall(method, args, target, invoke), invoke)
            }
        }

        @Suppress("UNCHECKED_CAST")
        val proxy = Proxy.newProxyInstance(
            interfaces.first().classLoader, interfaces, handler,
        ) as T
        return proxy to ShimBindResult(boundNames.toList(), unbound)
    }

    private fun invokeTarget(target: Any?, method: Method, args: Array<Any?>): Any? = try {
        method.invoke(target, *args)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.targetException
    }
}
