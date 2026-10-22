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
[ ! -d "$EXTEND_SOURCE/app/src/main/aidl" ] || cp -R "$EXTEND_SOURCE/app/src/main/aidl/." app/src/main/aidl/
cp -Rn "$EXTEND_SOURCE/app/src/main/res/." app/src/main/res/
mkdir -p hidden-api-stub
cp -R "$EXTEND_SOURCE/hidden-api-stub/." hidden-api-stub/

# Merge resources/strings and component declarations without replacing Saku's HOME activity.
python3 - <<'PY'
import os, xml.etree.ElementTree as ET
from pathlib import Path
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

# Add Extend permissions/features and components; skip its conflicting .MainActivity.
manifest=Path("app/src/main/AndroidManifest.xml")
dst=ET.parse(manifest); dr=dst.getroot()
ext=ET.parse(src/"app/src/main/AndroidManifest.xml").getroot()
A="{http://schemas.android.com/apk/res/android}name"
existing_perms={e.attrib.get(A) for e in dr.findall("uses-permission")}
for e in ext.findall("uses-permission"):
    if e.attrib.get(A) not in existing_perms: dr.insert(0,e)
existing_features={(e.attrib.get(A),e.attrib.get("{http://schemas.android.com/apk/res/android}required")) for e in dr.findall("uses-feature")}
for e in ext.findall("uses-feature"):
    key=(e.attrib.get(A),e.attrib.get("{http://schemas.android.com/apk/res/android}required"))
    if key not in existing_features: dr.insert(0,e)
da=dr.find("application"); ea=ext.find("application")
existing_components={(e.tag,e.attrib.get(A)) for e in da if e.tag in ("activity","service","provider","receiver")}
for e in ea:
    if e.tag not in ("activity","service","provider","receiver"): continue
    name=e.attrib.get(A,"")
    if name == ".MainActivity": continue
    key=(e.tag,name)
    if key not in existing_components:
        da.append(e)
# Ensure provider authorities unique to the embedded implementation.
for p in da.findall("provider"):
    if p.attrib.get(A)=="io.github.jqssun.displayextend.ShizukuProvider":
        p.set("{http://schemas.android.com/apk/res/android}authorities","com.sakuhome.launcher.displayextend.shizuku")
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
s=s.replace('\\nplugins { id("dev.rikka.tools.refine") version "4.1.0" apply false }\\n', '\\n')
# Ensure the refine plugin is declared in the existing root plugins block.
start=s.find("plugins {")
end=s.find("}", start)
if start < 0 or end < 0:
    raise SystemExit("Could not find root plugins block in build.gradle.kts")
declaration='id("dev.rikka.tools.refine") version "4.1.0" apply false'
if declaration not in s[start:end]:
    s=s[:end] + '    ' + declaration + "\\n" + s[end:]
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
