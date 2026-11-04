package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun letterboxAndReversedDragMapToSamePixels() {
        val display = Geometry.fit(1000,2000,600,800)
        assertEquals(Bounds(100f,0f,500f,800f),display)
        assertEquals(PixelCrop(250,500,500,1000), Geometry.crop(Bounds(400f,600f,200f,200f), display,1000,2000))
    }
    @Test fun oversizeAndTinySelectionCannotEscapeImage() {
        val display = Geometry.fit(2000,1000,600,800)
        assertEquals(PixelCrop(0,0,2000,1000),Geometry.crop(Bounds(-10f,-10f,800f,900f),display,2000,1000))
        assertNull(Geometry.crop(Bounds(3f,300f,5f,302f),display,2000,1000))
        assertNull(Geometry.crop(Bounds(10f,10f,20f,20f),display,2000,1000))
    }
}
