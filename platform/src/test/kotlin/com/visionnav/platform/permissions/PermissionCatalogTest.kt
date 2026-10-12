package com.visionnav.platform.permissions

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionCatalogTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `permissions per feature respect api level`() {
        assertThat(PermissionCatalog.permissionsFor(Feature.ACTIVITY, sdkInt = 28)).isEmpty()
        assertThat(PermissionCatalog.permissionsFor(Feature.ACTIVITY, sdkInt = 29))
            .containsExactly(Manifest.permission.ACTIVITY_RECOGNITION)
        assertThat(PermissionCatalog.permissionsFor(Feature.NOTIFICATIONS, sdkInt = 32)).isEmpty()
        assertThat(PermissionCatalog.permissionsFor(Feature.NOTIFICATIONS, sdkInt = 33))
            .containsExactly(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(PermissionCatalog.permissionsFor(Feature.SOS)).containsExactly(Manifest.permission.SEND_SMS)
        assertThat(PermissionCatalog.permissionsFor(Feature.VOICE)).containsExactly(Manifest.permission.RECORD_AUDIO)
        assertThat(PermissionCatalog.permissionsFor(Feature.CAMERA)).containsExactly(Manifest.permission.CAMERA)
    }

    @Test
    fun `missing reflects grants and fine location satisfies navigation`() {
        assertThat(PermissionCatalog.missing(app, Feature.NAVIGATION)).containsExactly(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertThat(PermissionCatalog.missing(app, Feature.NAVIGATION)).isEmpty()
        assertThat(PermissionCatalog.isGranted(app, Feature.VOICE)).isFalse()
    }

    // Resource *text* is not merged into library unit tests (includeAndroidResources is off), so
    // only the per-feature mapping is checked here.
    @Test
    fun `every feature has its own rationale string`() {
        val ids = Feature.entries.map { PermissionCatalog.rationaleRes(it) }
        assertThat(ids.toSet()).hasSize(Feature.entries.size)
        ids.forEach { assertThat(it).isNotEqualTo(0) }
    }
}
