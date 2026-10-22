#!/usr/bin/env bash
set -euo pipefail
EXTEND_REPO="https://github.com/kerenpandi71-collab/android-display-extend.git"
EXTEND_REF="feature/root-virtual-display-floating-keyboard-ui"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
git clone --depth 1 --branch "$EXTEND_REF" "$EXTEND_REPO" "$TMP/extend"
export EXTEND_SOURCE="$TMP/extend"

# Copy implementation while preserving the source package and AIDL layout.
mkdir -p app/src/main/java/io/github/jqssun/displayextend app/src/main/aidl app/src/main/res
cp -R "$EXTEND_SOURCE/app/src/main/java/io/github/jqssun/displayextend/." app/src/main/java/io/github/jqssun/displayextend/
# The embedded classes keep their original package, but Android generates R/BuildConfig
# from SakuLauncher's app namespace. Rewrite resource/config imports accordingly.
python3 - <<'PY'
from pathlib import Path
import re
root=Path("app/src/main/java/io/github/jqssun/displayextend")
for p in root.rglob("*.java"):
    s=p.read_text()
    s=s.replace("import io.github.jqssun.displayextend.R;", "import com.sakuhome.launcher.R;")
    s=s.replace("import io.github.jqssun.displayextend.BuildConfig;", "import com.sakuhome.launcher.BuildConfig;")
    if re.search(r"\bBuildConfig\.", s) and not re.search(r"import [\w.]+\.BuildConfig;", s):
        s=re.sub(r"(package [^;]+;)", r"\1\n\nimport com.sakuhome.launcher.BuildConfig;", s, count=1)
    if re.search(r"\bR\.", s) and not re.search(r"import [\w.]+\.R;", s):
        s=re.sub(r"(package [^;]+;)", r"\1\n\nimport com.sakuhome.launcher.R;", s, count=1)
    p.write_text(s)
PY
[ ! -d "$EXTEND_SOURCE/app/src/main/aidl" ] || cp -R "$EXTEND_SOURCE/app/src/main/aidl/." app/src/main/aidl/
cp -Rn "$EXTEND_SOURCE/app/src/main/res/." app/src/main/res/
# Remove a stale resource file from an earlier integration attempt; the source project has no values/styles.xml, and it duplicates Saku\x27s AppTheme in values/themes.xml.
rm -f app/src/main/res/values/styles.xml
mkdir -p hidden-api-stub
cp -R "$EXTEND_SOURCE/hidden-api-stub/." hidden-api-stub/

# Merge resources/strings and component declarations without replacing Saku's HOME activity.
python3 - <<'PY'
import os, xml.etree.ElementTree as ET
from pathlib import Path
# Preserve Android XML namespace prefixes when ElementTree rewrites the manifest.
ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
ET.register_namespace("tools", "http://schemas.android.com/tools")
src=Path(os.environ["EXTEND_SOURCE"])
target=Path("app/src/main/res/values/strings.xml")
incoming=src/"app/src/main/res/values/strings.xml"
if incoming.exists():
    target.parent.mkdir(parents=True, exist_ok=True)
    root=ET.parse(target).getroot() if target.exists() else ET.Element("resources")
    names={e.attrib.get("name") for e in root}
    for e in ET.parse(incoming).getroot():
        if e.attrib.get("name") not in names:
            root.append(e)
    ET.indent(root, space="    ")
    ET.ElementTree(root).write(target, encoding="utf-8", xml_declaration=True)

# Add Extend permissions/features and components while keeping Saku's HOME activity.
manifest=Path("app/src/main/AndroidManifest.xml")
dst=ET.parse(manifest); dr=dst.getroot()
ext=ET.parse(src/"app/src/main/AndroidManifest.xml").getroot()
# Earlier builds accidentally registered Extend classes as Saku-relative components.
# Remove those invalid duplicates; the actual implementation stays under its original package.
da=dr.find("application")
stale_names={
    ".OverlayDisplayActivity", ".LauncherActivity", ".TouchpadActivity",
    ".PureBlackActivity", ".AccessibilityConsentActivity", ".ManagedVirtualDisplayActivity",
    ".FloatingKeyboardService", ".MediaProjectionService", ".TouchpadAccessibilityService",
    ".FloatingButtonService", ".PureBlackActivity$ExitReceiver"
}
for node in list(da):
    if node.tag in ("activity","service","receiver") and node.attrib.get(A) in stale_names:
        da.remove(node)
# Register the application-wide diagnostics hook.
da.set(NS+"name", "com.sakuhome.launcher.SakuApplication")
A="{http://schemas.android.com/apk/res/android}name"
NS="{http://schemas.android.com/apk/res/android}"
existing_perms={e.attrib.get(A) for e in dr.findall("uses-permission")}
for e in ext.findall("uses-permission"):
    if e.attrib.get(A) not in existing_perms: dr.insert(0,e)
existing_features={(e.attrib.get(A),e.attrib.get(NS+"required")) for e in dr.findall("uses-feature")}
for e in ext.findall("uses-feature"):
    key=(e.attrib.get(A),e.attrib.get(NS+"required"))
    if key not in existing_features: dr.insert(0,e)
da=dr.find("application"); ea=ext.find("application")
# Fully qualify source-project components: manifest-relative names would resolve to
# com.sakuhome.launcher and launch the wrong classes after embedding.
for e in ea:
    if e.tag not in ("activity","service","provider","receiver"): continue
    name=e.attrib.get(A,"")
    if name.startswith("."):
        name="io.github.jqssun.displayextend"+name
        e.set(A,name)
    # The embedded MainActivity is a settings destination, not a second launcher.
    if name=="io.github.jqssun.displayextend.MainActivity":
        for intent_filter in list(e.findall("intent-filter")):
            actions={x.attrib.get(A) for x in intent_filter.findall("action")}
            categories={x.attrib.get(A) for x in intent_filter.findall("category")}
            if "android.intent.action.MAIN" in actions and (
                "android.intent.category.LAUNCHER" in categories or
                "android.intent.category.LEANBACK_LAUNCHER" in categories):
                e.remove(intent_filter)
    key=(e.tag,name)
    existing_components={(x.tag,x.attrib.get(A)) for x in da if x.tag in ("activity","service","provider","receiver")}
    if key not in existing_components:
        da.append(e)
# Ensure Shizuku provider authority is unique within the combined application.
for p in da.findall("provider"):
    if p.attrib.get(A)=="rikka.shizuku.ShizukuProvider":
        p.set(NS+"authorities","com.sakuhome.launcher.shizuku")
ET.indent(dr, space="    ")
dst.write(manifest, encoding="utf-8", xml_declaration=True)
PY

# Configure the hidden API stub and required libraries for the single app module.
python3 - <<'PY'
from pathlib import Path
p=Path("settings.gradle.kts")
s=p.read_text()
if 'include(":hidden-api-stub")' not in s: s += '\ninclude(":hidden-api-stub")\n'
p.write_text(s)
p=Path("build.gradle.kts")
s=p.read_text()
# Remove the exact standalone plugin block created by earlier integration attempts.
s=s.replace('\nplugins { id("dev.rikka.tools.refine") version "4.1.0" apply false }\n', '\n')
# Ensure the refine plugin is declared in the existing root plugins block.
start=s.find("plugins {")
end=s.find("}", start)
if start < 0 or end < 0:
    raise SystemExit("Could not find root plugins block in build.gradle.kts")
declaration='id("dev.rikka.tools.refine") version "4.1.0" apply false'
if declaration not in s[start:end]:
    s=s[:end] + '    ' + declaration + "\n" + s[end:]
p.write_text(s)
p=Path("app/build.gradle.kts")
s=p.read_text()
if 'id("dev.rikka.tools.refine")' not in s:
    s=s.replace('id("org.jetbrains.kotlin.android")','id("org.jetbrains.kotlin.android")\n    id("dev.rikka.tools.refine")')
s=s.replace('compileSdk = 35','compileSdk = 36').replace('targetSdk = 35','targetSdk = 35')
s=s.replace('    kotlinOptions {','    buildFeatures { aidl = true; buildConfig = true }\n    kotlinOptions {')
deps='''dependencies {
    compileOnly(project(":hidden-api-stub"))
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.navigation:navigation-fragment:2.9.7")
    implementation("androidx.navigation:navigation-ui:2.9.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.tools.refine:runtime:4.4.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.lifecycle:lifecycle-livedata:2.10.0")
}
'''
s=s[:s.index('dependencies {')] + deps
p.write_text(s)
PY

# Add an explicit entry in Saku's existing settings dialog.
python3 - <<'PY'
from pathlib import Path
p=Path("app/src/main/java/com/sakuhome/launcher/MainActivity.kt")
s=p.read_text()
needle='            "◌  Label menu aplikasi · $drawerLabelState",'
if needle in s and '"▣  Display Extend · Virtual Display"' not in s:
    s=s.replace(needle, needle+'\n            "▣  Display Extend · Virtual Display",')
    s=s.replace('                    17 -> {\n                        preferences.edit().putBoolean(KEY_HIDE_DRAWER_LABELS, !hideDrawerLabels).apply()\n                        buildScreen()\n                    }',
'''                    17 -> {
                        preferences.edit().putBoolean(KEY_HIDE_DRAWER_LABELS, !hideDrawerLabels).apply()
                        buildScreen()
                    }
                    18 -> {
                        try {
                            startActivity(Intent().setClassName(this, "io.github.jqssun.displayextend.MainActivity"))
                        } catch (e: Exception) {
                            Toast.makeText(this, "Display Extend belum bisa dibuka: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }''')
p.write_text(s)
PY
