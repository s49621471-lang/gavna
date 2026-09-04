package com.unique.core.common.profile

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random

class DeviceProfileTest {

    private val factory = DeviceProfileFactory(Random(0xC0FFEE))

    @Test fun `android id has the exact shape Settings Secure returns`() {
        repeat(200) {
            val p = factory.create("Test")
            assertThat(p.androidId).hasLength(16)
            assertThat(p.androidId).matches("[0-9a-f]{16}")
        }
    }

    @Test fun `rejects a malformed android id at construction`() {
        val good = factory.create("Test")
        for (bad in listOf("", "ABCDEF0123456789", "0123", "0123456789abcdefx")) {
            val e = runCatching { good.copy(androidId = bad) }.exceptionOrNull()
            assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test fun `distinct spaces get distinct identities`() {
        val a = factory.create("Space 1")
        val b = factory.create("Space 2")
        assertThat(a.androidId).isNotEqualTo(b.androidId)
        assertThat(a.instanceId).isNotEqualTo(b.instanceId)
        assertThat(a.profileId).isNotEqualTo(b.profileId)
    }

    @Test fun `reads are stable - the same field always returns the same value`() {
        val p = factory.create("Stable")
        val first = ProfileField.entries.associateWith { p.value(it) }
        repeat(50) {
            for (f in ProfileField.entries) assertThat(p.value(f)).isEqualTo(first[f])
        }
    }

    @Test fun `regenerate produces new identity but keeps profile identity and overrides`() {
        val p = factory.create("Profile A").copy(
            locale = "ru-RU",
            timeZone = "Europe/Moscow",
            build = BuildOverrides(model = "Pixel 9"),
        )
        val q = factory.regenerate(p)

        assertThat(q.profileId).isEqualTo(p.profileId)
        assertThat(q.displayName).isEqualTo(p.displayName)
        assertThat(q.generation).isEqualTo(p.generation + 1)

        assertThat(q.androidId).isNotEqualTo(p.androidId)
        assertThat(q.instanceId).isNotEqualTo(p.instanceId)
        assertThat(q.installId).isNotEqualTo(p.installId)
        assertThat(q.gsfId).isNotEqualTo(p.gsfId)

        // User-chosen settings survive a regenerate.
        assertThat(q.locale).isEqualTo("ru-RU")
        assertThat(q.timeZone).isEqualTo("Europe/Moscow")
        assertThat(q.build.model).isEqualTo("Pixel 9")
    }

    @Test fun `generated uuids are RFC 4122 version 4`() {
        repeat(100) {
            val p = factory.create("v4")
            assertThat(p.instanceId.version()).isEqualTo(4)
            assertThat(p.instanceId.variant()).isEqualTo(2)
            assertThat(p.installId.version()).isEqualTo(4)
        }
    }

    @Test fun `generated mac addresses are locally administered unicast`() {
        repeat(100) {
            val first = factory.create("mac").wifiMac.substringBefore(':').toInt(16)
            assertThat(first and 0x02).isEqualTo(0x02) // locally administered
            assertThat(first and 0x01).isEqualTo(0x00) // unicast
        }
    }

    @Test fun `build overrides default to reporting the host values`() {
        assertThat(factory.create("x").build.isEmpty).isTrue()
        assertThat(factory.create("x").value(ProfileField.BUILD_MODEL)).isNull()
    }

    @Test fun `every profile field is reachable through the single accessor`() {
        val p = factory.create("coverage").copy(
            locale = "en-US", timeZone = "UTC",
            build = BuildOverrides("fp", "m", "mf", "b", "d", "pr"),
        )
        for (field in ProfileField.entries) {
            assertThat(p.value(field)).isNotNull()
        }
    }
}
