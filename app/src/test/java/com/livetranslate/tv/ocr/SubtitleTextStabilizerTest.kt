package com.livetranslate.tv.ocr

import com.livetranslate.tv.ocr.SubtitleTextStabilizer.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTextStabilizerTest {

    private fun feed(s: SubtitleTextStabilizer, vararg samples: String?) = samples.map { s.onSample(it) }

    @Test
    fun showsLineOnlyAfterTwoMatchingReadings() {
        val s = SubtitleTextStabilizer()
        assertNull(s.onSample("We need to move now."))
        assertEquals(Event.Show(1, "We need to move now."), s.onSample("We need to move now."))
        assertNull(s.onSample("We need to move now."))
    }

    @Test
    fun ocrNoiseCountsAsTheSameLine() {
        val s = SubtitleTextStabilizer()
        assertNull(s.onSample("We need to rnove now."))
        assertEquals(Event.Show(1, "We need to move now."), s.onSample("We need to move now."))
        // A slightly different misread of the same line is not a new subtitle.
        assertNull(s.onSample("We need t0 move now"))
    }

    @Test
    fun singleFrameGlitchIsIgnored() {
        val s = SubtitleTextStabilizer()
        feed(s, "Hold the line!", "Hold the line!")
        assertNull(s.onSample("HUD 120/120"))
        assertNull(s.onSample("Hold the line!"))
    }

    @Test
    fun newLineGetsNewId() {
        val s = SubtitleTextStabilizer()
        feed(s, "Is anyone there?", "Is anyone there?")
        assertNull(s.onSample("Stay close and keep your voice down."))
        assertEquals(Event.Show(2, "Stay close and keep your voice down."), s.onSample("Stay close and keep your voice down."))
    }

    @Test
    fun typewriterTextUpdatesSameLine() {
        val s = SubtitleTextStabilizer()
        assertNull(s.onSample("Check the"))
        assertEquals(Event.Show(1, "Check the map,"), s.onSample("Check the map,"))
        assertEquals(Event.Update(1, "Check the map, the exit"), s.onSample("Check the map, the exit"))
        assertEquals(Event.Update(1, "Check the map, the exit is north."), s.onSample("Check the map, the exit is north."))
        assertNull(s.onSample("Check the map, the exit is north."))
    }

    @Test
    fun clearsAfterTwoEmptyReadings() {
        val s = SubtitleTextStabilizer()
        feed(s, "Is anyone there?", "Is anyone there?")
        assertNull(s.onSample(null))
        assertEquals(Event.Clear(1), s.onSample(""))
        assertNull(s.onSample(null))
    }

    @Test
    fun shortJunkIsTreatedAsEmpty() {
        val s = SubtitleTextStabilizer()
        assertNull(s.onSample("A"))
        assertNull(s.onSample(" . "))
        assertNull(s.onSample("A"))
    }

    @Test
    fun sameTextAfterClearIsShownAgain() {
        val s = SubtitleTextStabilizer()
        feed(s, "Yes.", "Yes.", null, null)
        assertNull(s.onSample("Yes."))
        assertEquals(Event.Show(2, "Yes."), s.onSample("Yes."))
    }

    @Test
    fun singleReadModeShowsImmediatelyAndMergesRereads() {
        val s = SubtitleTextStabilizer(stableSamples = 1)
        assertEquals(Event.Show(1, "Dunn: You gotta listen to heavy metal."), s.onSample("Dunn: You gotta listen to heavy metal."))
        // A noisy re-read of the same line is not a new line.
        assertNull(s.onSample("Dunn: You gotta listen to heavy rnetal"))
        assertEquals(
            Event.Show(2, "Cho: I was born in Seoul, you hick."),
            s.onSample("Cho: I was born in Seoul, you hick."),
        )
    }

    @Test
    fun fragmentOfRecentLineDuringFadeIsIgnored() {
        val s = SubtitleTextStabilizer(stableSamples = 1)
        assertEquals(Event.Show(1, "Cho: Oh, your Korean is excellent."), s.onSample("Cho: Oh, your Korean is excellent."))
        assertNull(s.onSample("ean is excelle"))
        assertNull(s.onSample(null))
        assertEquals(Event.Clear(1), s.onSample(null))
        // Still a fragment of a recent line after the clear.
        assertNull(s.onSample("your Korean is"))
        assertEquals(Event.Show(2, "Ju Kwon: Sergeant, sir!"), s.onSample("Ju Kwon: Sergeant, sir!"))
    }
}
