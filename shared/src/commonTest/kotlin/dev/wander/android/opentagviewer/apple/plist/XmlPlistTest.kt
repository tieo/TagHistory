package io.github.tieo.taghistory.apple.plist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XmlPlistTest {

    @Test
    fun parsesFullEnvelope() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
              <dict>
                <key>greeting</key>
                <string>hello</string>
                <key>count</key>
                <integer>42</integer>
                <key>ratio</key>
                <real>3.5</real>
                <key>on</key>
                <true/>
                <key>off</key>
                <false/>
                <key>blob</key>
                <data>aGVsbG8=</data>
              </dict>
            </plist>
        """.trimIndent()

        val parsed = XmlPlist.parse(xml)
        val dict = assertIs<PlistValue.Dict>(parsed)
        assertEquals("hello", dict.string("greeting"))
        assertEquals(42L, dict.int64("count"))
        assertEquals(3.5, (dict["ratio"] as PlistValue.Real).value)
        assertEquals(true, dict.bool("on"))
        assertEquals(false, dict.bool("off"))
        assertEquals("hello".encodeToByteArray().toList(), dict.data("blob")!!.toList())
    }

    @Test
    fun acceptsBareDictFragmentLikeAppleSpdQuirk() {
        // Apple's SPD endpoint sometimes returns a bare <dict> fragment with
        // no XML declaration and no <plist> wrapper. Python's plistlib
        // accepts this; dd-plist rejected it. We MUST accept it.
        val fragment = "<dict><key>k</key><string>v</string></dict>"
        val parsed = XmlPlist.parse(fragment)
        val dict = assertIs<PlistValue.Dict>(parsed)
        assertEquals("v", dict.string("k"))
    }

    @Test
    fun acceptsBareArrayFragment() {
        val fragment = "<array><integer>1</integer><integer>2</integer></array>"
        val parsed = XmlPlist.parse(fragment)
        val arr = assertIs<PlistValue.Array>(parsed)
        assertEquals(2, arr.size)
        assertEquals(1L, (arr[0] as PlistValue.Int64).value)
        assertEquals(2L, (arr[1] as PlistValue.Int64).value)
    }

    @Test
    fun decodesEntities() {
        val xml = "<plist><string>a &amp; b &lt; c &gt; d &quot;e&quot; &#65;</string></plist>"
        val s = assertIs<PlistValue.Str>(XmlPlist.parse(xml))
        assertEquals("a & b < c > d \"e\" A", s.value)
    }

    @Test
    fun nestedStructures() {
        val xml = """
            <plist><dict>
              <key>outer</key>
              <dict>
                <key>inner</key>
                <array>
                  <string>a</string>
                  <string>b</string>
                </array>
              </dict>
            </dict></plist>
        """.trimIndent()

        val dict = assertIs<PlistValue.Dict>(XmlPlist.parse(xml))
        val outer = assertNotNull(dict.dict("outer"))
        val inner = assertNotNull(outer.array("inner"))
        assertEquals("a", (inner[0] as PlistValue.Str).value)
        assertEquals("b", (inner[1] as PlistValue.Str).value)
    }

    @Test
    fun preservesInsertionOrder() {
        // Order matters for diff-stability against pysrp/findmy.
        val xml = """
            <plist><dict>
              <key>zeta</key><string>z</string>
              <key>alpha</key><string>a</string>
              <key>mu</key><string>m</string>
            </dict></plist>
        """.trimIndent()
        val dict = assertIs<PlistValue.Dict>(XmlPlist.parse(xml))
        assertEquals(listOf("zeta", "alpha", "mu"), dict.entries.keys.toList())
    }

    @Test
    fun encodeThenParseRoundTrips() {
        val original = plistDictOf(
            "name" to "Example".asPlist(),
            "count" to 7L.asPlist(),
            "flag" to true.asPlist(),
            "blob" to byteArrayOf(1, 2, 3, 4).asPlist(),
            "items" to plistArrayOf("one".asPlist(), 2L.asPlist()),
        )
        val xml = XmlPlist.encode(original)
        val parsed = XmlPlist.parse(xml)
        assertEquals(original, parsed)
    }

    @Test
    fun iso8601DateRoundTrip() {
        val xml = "<plist><date>2024-03-01T12:00:00Z</date></plist>"
        val d = assertIs<PlistValue.Date>(XmlPlist.parse(xml))
        // Re-encoding and parsing yields the same instant.
        val reencoded = XmlPlist.encode(d)
        val reparsed = assertIs<PlistValue.Date>(XmlPlist.parse(reencoded))
        assertEquals(d.epochMillis, reparsed.epochMillis)
        // Sanity: the expected epoch millis for 2024-03-01T12:00:00Z.
        assertEquals(1709294400000L, d.epochMillis)
    }

    @Test
    fun unknownTagRaisesParseException() {
        val xml = "<plist><bogus>x</bogus></plist>"
        assertFailsWith<PlistParseException> { XmlPlist.parse(xml) }
    }

    @Test
    fun opaqueAcceptsBothStringAndData() {
        // The `c` cookie GSA quirk — currently NSString, historically NSData.
        // The typed accessor must accept both so the bug we shipped last
        // week ("String cannot be cast to byte[]") stays impossible.
        val asString = plistDictOf("c" to "cookie-string".asPlist())
        val asData = plistDictOf("c" to "cookie-bytes".encodeToByteArray().asPlist())

        assertEquals("cookie-string", asString.opaque("c")!!.decodeToString())
        assertEquals("cookie-bytes", asData.opaque("c")!!.decodeToString())
        assertTrue(plistDictOf().opaque("missing") == null)

        // A bogus type raises — not a silent null, not a cast exception.
        val wrong = plistDictOf("c" to 1L.asPlist())
        assertFailsWith<IllegalArgumentException> { wrong.opaque("c") }
    }
}
