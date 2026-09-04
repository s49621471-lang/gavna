package android.app

/**
 * Test double for the framework's hidden `IApplicationThread`.
 *
 * The predicate under test identifies caller-identity calls by the *fully-qualified name*
 * of this parameter type, and the real interface is not in the SDK stubs. Declaring one
 * with the same name here is what lets the predicate be tested at all; it is never on the
 * production classpath, where the real framework interface is.
 */
interface IApplicationThread
