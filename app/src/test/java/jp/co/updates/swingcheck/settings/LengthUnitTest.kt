package jp.co.updates.swingcheck.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LengthUnitTest {
    @Test
    fun inchForUsLiberiaMyanmar() {
        assertEquals(LengthUnit.INCH, LengthUnit.defaultFor("US"))
        assertEquals(LengthUnit.INCH, LengthUnit.defaultFor("LR"))
        assertEquals(LengthUnit.INCH, LengthUnit.defaultFor("MM"))
        assertEquals(LengthUnit.INCH, LengthUnit.defaultFor("us"))
    }

    @Test
    fun cmForOthers() {
        assertEquals(LengthUnit.CM, LengthUnit.defaultFor("JP"))
        assertEquals(LengthUnit.CM, LengthUnit.defaultFor("GB"))
        assertEquals(LengthUnit.CM, LengthUnit.defaultFor(""))
        assertEquals(LengthUnit.CM, LengthUnit.defaultFor(null))
    }
}
