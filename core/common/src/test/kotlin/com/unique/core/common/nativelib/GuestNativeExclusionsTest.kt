package com.unique.core.common.nativelib

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the path redirector is told to leave alone.
 *
 * The list itself is evidence, not taste — every entry names a run that put it there —
 * so what is worth testing is the shape of the answer: that the protector observed on
 * hardware is in it, that an instance can add to it, and above all that no input can turn
 * it into an exclusion that matches every library and silently switches redirection off
 * for the whole process.
 */
class GuestNativeExclusionsTest {

    @Test fun `the protector observed on hardware is excluded`() {
        assertThat(GuestNativeExclusions.forGuest()).contains("libgrave.so")
    }

    @Test fun `UNIQUE's own native library is never hooked`() {
        assertThat(GuestNativeExclusions.forGuest()).contains("libunique_native.so")
    }

    @Test fun `an instance adds to the built-in list rather than replacing it`() {
        val out = GuestNativeExclusions.forGuest(listOf("libmyprotector.so"))
        assertThat(out).contains("libmyprotector.so")
        assertThat(out).contains("libgrave.so")
    }

    @Test fun `a blank entry is dropped, not turned into a match-everything rule`() {
        // The exclusion is a substring test in C++, so "" contains-matches every path.
        // A stray blank line in the override file would then exclude the guest's entire
        // native code from redirection, with nothing in the log to say why.
        val out = GuestNativeExclusions.forGuest(listOf("", "   ", "\t"))
        assertThat(out).doesNotContain("")
        assertThat(out).isEqualTo(GuestNativeExclusions.forGuest())
    }

    @Test fun `an entry is trimmed, because a file's lines carry whitespace`() {
        assertThat(GuestNativeExclusions.forGuest(listOf("  libx.so  "))).contains("libx.so")
    }

    @Test fun `the same name twice is one exclusion`() {
        val out = GuestNativeExclusions.forGuest(listOf("libgrave.so", "libgrave.so"))
        assertThat(out.count { it == "libgrave.so" }).isEqualTo(1)
    }
}
