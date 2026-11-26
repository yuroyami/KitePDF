package io.github.yuroyami.kitepdf.media

import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationMaskLandscapeLeft
import platform.UIKit.UIInterfaceOrientationMaskLandscapeRight
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIInterfaceOrientationMaskPortraitUpsideDown
import platform.UIKit.UIInterfaceOrientationPortrait
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The iOS side of a full-screen video: the dialog it opens and the orientation it restores (#768). */
class FullScreenIosTest {

    @Test
    fun each_orientation_restores_to_its_own_mask() {
        // A wrong mask asks the scene for another orientation when full screen ends.
        assertEquals(UIInterfaceOrientationMaskPortrait, maskOf(UIInterfaceOrientationPortrait))
        assertEquals(UIInterfaceOrientationMaskPortraitUpsideDown, maskOf(UIInterfaceOrientationPortraitUpsideDown))
        assertEquals(UIInterfaceOrientationMaskLandscapeLeft, maskOf(UIInterfaceOrientationLandscapeLeft))
        assertEquals(UIInterfaceOrientationMaskLandscapeRight, maskOf(UIInterfaceOrientationLandscapeRight))
    }

    @Test
    fun the_full_screen_dialog_covers_the_screen_and_closes_on_back_only() {
        val properties = fullScreenDialogProperties()
        assertTrue(properties.dismissOnBackPress)
        assertFalse(properties.dismissOnClickOutside)
        assertFalse(properties.usePlatformDefaultWidth)
    }
}
