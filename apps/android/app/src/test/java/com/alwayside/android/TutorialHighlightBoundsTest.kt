package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class TutorialHighlightBoundsTest {
    @Test fun edgeBubbleKeepsItsWholeVisibleTouchAreaOutsideTheDimmedRegion() {
        val bounds=TutorialHighlightBounds()
        assertTrue(bounds.update(272f,200f,48f,48f,320f,480f,6f))
        assertEquals(266f,bounds.left,0f);assertEquals(320f,bounds.right,0f)
        assertTrue(bounds.top<200f);assertTrue(bounds.bottom>248f)
    }
    @Test fun unmeasuredDetachedAndOffScreenTargetsCannotRequestAFullGrayMask() {
        val bounds=TutorialHighlightBounds()
        assertFalse(bounds.update(0f,0f,0f,0f,320f,480f,6f))
        assertFalse(bounds.update(0f,0f,48f,48f,0f,0f,6f))
        assertFalse(bounds.update(320f,100f,48f,48f,320f,480f,6f))
        assertFalse(bounds.update(0f,480f,48f,48f,320f,480f,6f))
    }
    @Test fun aPartlyClippedTargetStaysVisibleAndBoundsRemainInsideTheScene() {
        val bounds=TutorialHighlightBounds()
        assertTrue(bounds.update(-8f,-10f,48f,48f,320f,480f,6f))
        assertEquals(0f,bounds.left,0f);assertEquals(0f,bounds.top,0f)
        assertEquals(46f,bounds.right,0f);assertEquals(44f,bounds.bottom,0f)
    }
}
