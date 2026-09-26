package tw.avianjay.airplaydroid.protocol.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The comparison rule, which is the one that decides whether an update is ever
 * offered.
 *
 * The cases that matter are not the happy ones -- `1.2.3 > 1.2.2` is not worth a
 * test -- but the ones where a naive implementation quietly goes wrong: a
 * nightly suffix, a `v` prefix, a missing component, and a name that is not a
 * version at all.
 */
class AppVersionTest {

    private fun v(text: String?) = AppVersion.parse(text)

    @Test
    fun ordersByComponentNotLexically() {
        // The classic string-sort bug: "1.10.0" < "1.9.0" lexically.
        assertTrue(v("1.10.0")!! > v("1.9.0")!!)
        assertTrue(v("1.0.10")!! > v("1.0.9")!!)
        assertTrue(v("2.0")!! > v("1.99.99")!!)
    }

    @Test
    fun missingComponentsAreZero() {
        assertEquals(v("1.2"), v("1.2.0"))
        assertEquals(v("1.2.0.0"), v("1.2"))
        assertEquals(0, v("1.2")!!.compareTo(v("1.2.0.0")!!))
    }

    @Test
    fun trailingZerosAreDroppedFromTheString() {
        assertEquals("1.2", v("1.2.0.0")!!.toString())
        assertEquals("0", v("0.0.0")!!.toString())
    }

    @Test
    fun acceptsAVPrefixAndSurroundingSpace() {
        assertEquals(v("1.2.3"), v("v1.2.3"))
        assertEquals(v("1.2.3"), v("  V1.2.3  "))
    }

    @Test
    fun ignoresAPreReleaseSuffix() {
        // The suffix is dropped, so these compare EQUAL. That is intentional:
        // which nightly is newer is a question about versionCode, and
        // UpdateSelector answers it there. A name-based rule here would make
        // 0.2.0-nightly.7 look older than 0.2.0 and hide the nightly.
        assertEquals(v("0.2.0"), v("0.2.0-nightly.7"))
        assertEquals(v("0.2.0"), v("0.2.0-rc.1+build.5"))
        assertEquals(v("1.0.0"), v("1.0.0-nightly.42.03a7e72"))
    }

    @Test
    fun rejectsNamesThatCarryNoVersion() {
        // "nightly" is a release *tag*. Treating it as 0 would make every
        // nightly compare older than every real release.
        assertNull(v("nightly"))
        assertNull(v(""))
        assertNull(v("   "))
        assertNull(v(null))
        assertNull(v("v"))
    }

    @Test
    fun aLeadingNumberInsideATagStillCounts() {
        // The workflow tags nightlies with a real versionName, so this shape is
        // the one actually seen: "0.1.0-nightly.42.03a7e72".
        assertEquals(v("0.1.0"), v("0.1.0-nightly.42.03a7e72"))
    }

    @Test
    fun zeroIsTheFloor() {
        assertEquals(0, AppVersion.ZERO.compareTo(v("0")!!))
        assertTrue(v("0.0.1")!! > AppVersion.ZERO)
    }
}
