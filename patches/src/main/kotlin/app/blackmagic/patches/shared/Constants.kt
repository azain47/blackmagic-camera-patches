package app.blackmagic.patches.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

/**
 * Compatibility declaration for Blackmagic Camera.
 *
 * The app ships as a split APK bundle on Google Play. Patching targets `base.apk`
 * (all patched code lives in its dex); the `split_config.*` modules are unchanged.
 *
 * `signatures` is the SHA-256 of the genuine Play signing certificate, so patch
 * managers can verify the user supplied the real upstream APK before patching.
 */
object Constants {
    val COMPATIBILITY_BLACKMAGIC_CAM = Compatibility(
        name = "Blackmagic Camera",
        packageName = "com.blackmagicdesign.android.blackmagiccam",
        appIconColor = 0xFF1A1A,
        signatures = setOf(
            "8a889ddc2cac452968c5f2c62d339dd2f7f9dab526864b48ab53ee0a3e140cd9"
        ),
        targets = listOf(
            AppTarget(
                version = "3.5.0.0066",
                minSdk = 26
            )
        )
    )
}
