package com.unique.core.common.profile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The profile a virtual process reads must be the one the database holds.
 *
 * Worth its own test because the reader is a different process from the writer and, after
 * an update, a different build: a field lost in the round trip becomes an instance whose
 * identity silently changes, which is precisely what the profile exists to prevent.
 */
class DeviceProfileCodecTest {

    private fun sample() = DeviceProfileFactory().create("Profile 1")

    @Test fun `a profile round-trips through its text form`() {
        val original = sample()
        val decoded = DeviceProfileCodec.decode(DeviceProfileCodec.encode(original))
        assertThat(decoded).isEqualTo(original)
    }

    @Test fun `every identity field survives the round trip`() {
        val original = sample()
        val decoded = DeviceProfileCodec.decode(DeviceProfileCodec.encode(original))!!
        // Enumerated rather than listed by hand: a field added to ProfileField and not to
        // the codec fails here instead of silently reverting to the host's value.
        for (field in ProfileField.entries) {
            assertThat(decoded.value(field)).isEqualTo(original.value(field))
        }
    }

    @Test fun `optional sections survive when present and are absent when not`() {
        val withScreen = sample().copy(
            screen = ScreenProfile(1080, 2400, 420),
            build = BuildOverrides(model = "Pixel 8", manufacturer = "Google"),
            locale = "en-GB",
            timeZone = "Europe/London",
        )
        val decoded = DeviceProfileCodec.decode(DeviceProfileCodec.encode(withScreen))!!
        assertThat(decoded.screen).isEqualTo(ScreenProfile(1080, 2400, 420))
        assertThat(decoded.build.model).isEqualTo("Pixel 8")
        assertThat(decoded.locale).isEqualTo("en-GB")

        val bare = DeviceProfileCodec.decode(DeviceProfileCodec.encode(sample()))!!
        assertThat(bare.screen).isNull()
        assertThat(bare.build.isEmpty).isTrue()
    }

    @Test fun `a half-written profile decodes to null, not to invented values`() {
        // An instance whose identity is half-read is worse than one that falls back to
        // the host's: the halves would disagree with each other.
        assertThat(DeviceProfileCodec.decode("")).isNull()
        assertThat(DeviceProfileCodec.decode("androidId=deadbeefdeadbeef")).isNull()
        assertThat(DeviceProfileCodec.decode("profileId=x\nandroidId=nothex0123456789")).isNull()
        assertThat(DeviceProfileCodec.decode("profileId=x\nandroidId=deadbeefdeadbeef")).isNull()
    }
}
