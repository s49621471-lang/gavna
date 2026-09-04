package com.unique.core.hook

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflection helpers with caching.
 *
 * Everything the hook layer touches is looked up by name, because the alternative —
 * compiling against hidden AIDL stubs — is what makes engines of this kind break on
 * every Android release. Lookups are cached because they happen on process-start paths
 * where cold-start time is the metric users notice.
 */
object Reflect {

    private val classes = HashMap<String, Class<*>?>()
    private val fields = HashMap<String, Field?>()
    private val methods = HashMap<String, Method?>()

    @Synchronized
    fun findClass(name: String, loader: ClassLoader? = null): Class<*>? =
        classes.getOrPut(name) {
            runCatching { Class.forName(name, false, loader ?: Reflect::class.java.classLoader) }
                .getOrNull()
        }

    @Synchronized
    fun findField(clazz: Class<*>, name: String): Field? =
        fields.getOrPut("${clazz.name}#$name") {
            var c: Class<*>? = clazz
            var found: Field? = null
            while (c != null && found == null) {
                found = runCatching { c!!.getDeclaredField(name) }.getOrNull()
                c = c.superclass
            }
            found?.apply { isAccessible = true }
        }

    @Synchronized
    fun findMethod(clazz: Class<*>, name: String, vararg params: Class<*>): Method? =
        methods.getOrPut("${clazz.name}#$name(${params.joinToString { it.name }})") {
            var c: Class<*>? = clazz
            var found: Method? = null
            while (c != null && found == null) {
                found = runCatching { c!!.getDeclaredMethod(name, *params) }.getOrNull()
                c = c.superclass
            }
            found?.apply { isAccessible = true }
        }

    /** Finds a method by name alone, when the signature varies across releases. */
    @Synchronized
    fun findMethodByName(clazz: Class<*>, name: String): Method? =
        methods.getOrPut("${clazz.name}#$name(*)") {
            var c: Class<*>? = clazz
            while (c != null) {
                c.declaredMethods.firstOrNull { it.name == name }?.let {
                    it.isAccessible = true
                    return@getOrPut it
                }
                c = c.superclass
            }
            null
        }

    fun get(clazz: Class<*>, name: String, target: Any? = null): Any? =
        findField(clazz, name)?.get(target)

    fun set(clazz: Class<*>, name: String, target: Any?, value: Any?): Boolean {
        val f = findField(clazz, name) ?: return false
        return runCatching { f.set(target, value); true }.getOrDefault(false)
    }

    fun call(clazz: Class<*>, name: String, target: Any?, vararg args: Any?): Any? =
        findMethodByName(clazz, name)?.invoke(target, *args)

    /** Clears the caches. Only useful in tests. */
    @Synchronized
    fun reset() {
        classes.clear(); fields.clear(); methods.clear()
    }
}
