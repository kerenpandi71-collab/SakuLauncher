# DroidOS FOSS Launcher module

This module incorporates the launcher component from the FOSS/GPLv3 line of [Katsuyamaki/DroidOS](https://github.com/Katsuyamaki/DroidOS), commit `f10ef2cea60aa7829876ba5a60b6a8068f19c901`, originally under `Cover-Screen-Launcher/app`.

The launcher is built as an Android library inside SakuLauncher so its original floating launcher, drawer, and window-management implementation can share the same package and permissions as the bundled DroidOS trackpad module. Its standalone launcher-icon entry point is removed; Saku opens the mode through one combined setup screen.

The source line used is the repository's `community/fdroid` FOSS branch, licensed GPLv3. See `LICENSE`.

A single Shizuku provider is supplied by the bundled trackpad module; the launcher library therefore does not declare a conflicting duplicate provider.
