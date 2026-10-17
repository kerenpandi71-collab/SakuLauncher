# DroidOS Trackpad module integrated into SakuLauncher

This module is adapted from the GPLv3 FOSS branch of DroidOS:
- Upstream: https://github.com/katsuyamaki/DroidOS/tree/community/fdroid/Cover-Screen-Trackpad
- Source commit: f10ef2cea60aa7829876ba5a60b6a8068f19c901
- License: GNU GPL v3; see this directory's LICENSE.

The module is built as an Android library and its activities/services are packaged into SakuLauncher. The Saku settings item "Mode Trackpad DroidOS" starts the trackpad setup/service; toggling it off sends the module's STOP_SERVICE command.

The trackpad still requires the Android permissions requested by its setup flow, including Accessibility, overlay, and Shizuku where applicable. The integration does not silently grant these permissions.
