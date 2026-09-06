package com.unique.core.vam

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The rule that keeps one answer to "where are UNIQUE's own files".
 *
 * `VirtualPathModel` is constructed from `context.filesDir` in a dozen places, and that
 * was correct for as long as `getFilesDir()` inside a `:vappN` could only mean UNIQUE's
 * directory. [GuestIdentityPaths] deliberately ends that. What has to hold afterwards is
 * that the *first* answer — taken from UNIQUE's own context, before the swap — is the one
 * every later caller gets, however convincing the context they happen to be holding.
 */
class HostPathsTest {

    @Before fun clear() = HostPaths.forget()
    @After fun clean() = HostPaths.forget()

    @Test fun `the first answer is the one that is kept`() {
        assertThat(HostPaths.remember("/data/user/0/com.unique/files"))
            .isEqualTo("/data/user/0/com.unique/files")
        // What a guest's context would say after the identity swap. It arrives second and
        // is therefore not an answer to this question.
        assertThat(HostPaths.remember("/data/user/0/com.axlebolt.standoff2/files"))
            .isEqualTo("/data/user/0/com.unique/files")
        assertThat(HostPaths.known).isEqualTo("/data/user/0/com.unique/files")
    }

    @Test fun `nothing is remembered until something is`() {
        assertThat(HostPaths.known).isNull()
        // A context that cannot answer must not poison the memory with a blank, because
        // `VirtualPathModel("")` produces paths that look absolute and are not.
        assertThat(HostPaths.remember(null as String?)).isNull()
        assertThat(HostPaths.remember("")).isNull()
        assertThat(HostPaths.known).isNull()
        assertThat(HostPaths.remember("/data/user/0/com.unique/files"))
            .isEqualTo("/data/user/0/com.unique/files")
    }

    @Test fun `a process that never grafted has nothing to offer`() {
        // UNIQUE's own processes never call `remember`, and `known` being null there is
        // what makes the fallback in `filesRoot` correct rather than a guess.
        assertThat(HostPaths.known).isNull()
    }
}
