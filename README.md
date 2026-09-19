# DomeVR Player

Native Cardboard VR video player for Android with in-headset SMB (SMB2/3)
browsing and direct streaming — no downloads, no transcoding.

- Phone + Cardboard stereo rendering with lens-distortion correction
- Browse servers, folders, and files without leaving VR (gaze + dwell)
- Projections: Flat 2D, SBS/OU, 180/220/270/360 domes, fisheye
- Look-up play menu: transport controls, volume, zoom, folder queue, seek
- 3D settings page: video type, screen shape, lens, FOV, zoom, IPD

## Build

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK 34, Kotlin 1.9.x. SMB passwords are typed once in
the 2D app and stored encrypted on-device; never in VR.

## License

GPL-3.0-only — see [LICENSE](LICENSE). Anyone distributing a product
built from this code must provide its full source under the same terms.
