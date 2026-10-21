package org.koitharu.kotatsu.core.parser

import org.junit.Assert.*
import org.junit.Test

class HitomiImageRoutingTest {
    @Test fun refreshReplacesOldServerAndGeneration() {
        val hash="0".repeat(61)+"abc"
        val id=0xcab
        val old=HitomiImageRouting.parse("var o = 0; switch(g) {case $id: o = 1; break;} b: '100/'")
        val fresh=HitomiImageRouting.parse("var o=1;switch(g){case $id : o=0;break;} b: \"200/\"")
        assertEquals("https://a2.gold-usergeneratedcontent.net/100/$id/$hash.avif",old.pageUrl(hash))
        assertEquals("https://a1.gold-usergeneratedcontent.net/200/$id/$hash.avif",fresh.pageUrl(hash))
    }
    @Test(expected=java.io.IOException::class) fun invalidRoutingIsReported() {
        HitomiImageRouting.parse("<html>Verification required</html>")
    }
}
