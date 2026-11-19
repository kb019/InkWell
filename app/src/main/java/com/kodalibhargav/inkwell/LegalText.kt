package com.kodalibhargav.inkwell

/**
 * The policies shown under Settings, so they can be read without a network connection.
 * Keep them in sync with docs/privacy-policy.md and docs/terms-of-service.md.
 */
const val SUPPORT_EMAIL = "jelu.labs.apps@gmail.com"

val PRIVACY_POLICY =
    """
Last updated: 7 November 2026

Inkwell is a live wallpaper app. It does not collect, store, share or sell any personal data.

What the app does on your device
• It draws an ink animation and responds to your touch on the wallpaper.
• It saves your choices (selected style, ink colour and settings) on your device only.
• If you turn on Tilt or Shake, it reads the motion sensor on your device to move the ink. This is off by default, only runs while the wallpaper is visible, and the readings are never stored or sent anywhere.
• When you tap Save, it writes one image of the ink to your gallery (Pictures/Inkwell). It does not read your photos.
• If you tap Rate & give feedback, the Google Play page for Inkwell opens in the Play Store app, which is governed by Google's own privacy policy.
• If you tap Send feedback (shown when Inkwell was not installed from Google Play), your email app opens with a draft addressed to us. The draft lists your app version, Android version and device model. You can edit or delete it before sending, and the app itself sends nothing.

What the app does not do
• It does not connect to the internet.
• It does not use accounts, analytics, advertising or tracking.
• It does not access your photos, contacts, location, microphone or camera.

Children
Inkwell can be used by children. Because the app collects no personal information from anyone, including children:
• It has no ads, accounts, analytics or tracking.
• It has no internet access and no links that lead out of the app.
• It does not share anything with third parties, because it collects nothing.

Parents and guardians who have questions can contact us at the address below.

Changes
If this policy changes, the updated version will be posted at the same address with a new date.

Contact
Questions: $SUPPORT_EMAIL
""".trim()

val TERMS_OF_SERVICE =
    """
Last updated: 16 November 2026

By installing or using Inkwell you agree to these terms. If you do not agree, please do not use the app.

The app
Inkwell is a live wallpaper that draws a fluid ink animation on your device. It is free and has no ads or purchases.

Licence
The Inkwell source code is released under the MIT Licence. These terms cover your use of the app itself.

Using the app
• You may use Inkwell for your own purposes, such as setting it as your wallpaper or sharing images you save from it.
• Please do not use the app in a way that breaks the law or harms others.

Images you save
Images you save with the Save button are created on your device and belong to you. Inkwell makes no claim over them.

Battery and performance
A live wallpaper uses the screen and the graphics chip. Drawing pauses when the wallpaper is not visible, but you use the app at your own discretion, and results vary between devices.

No warranty
The app is provided "as is", without warranty of any kind, express or implied, including fitness for a particular purpose. We do not promise that it will be error free or that it will work on every device.

Limit of liability
To the extent the law allows, the author is not liable for any loss or damage arising from the use of the app.

Children
Inkwell can be used by children. Parents and guardians are responsible for supervising a child's use of the device and the app.

Changes
These terms may change. The updated version will be shown in the app with a new date.

Contact
Questions: $SUPPORT_EMAIL
""".trim()
