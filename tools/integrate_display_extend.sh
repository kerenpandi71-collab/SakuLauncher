#!/usr/bin/env bash
set -euo pipefail

EXTEND_REPO="https://github.com/kerenpandi71-collab/android-display-extend.git"
EXTEND_REF="feature/root-virtual-display-floating-keyboard-ui"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
git clone --depth 1 --branch "$EXTEND_REF" "$EXTEND_REPO" "$TMP/extend"

# Copy Java/Kotlin, AIDL and resources while retaining Extend's original package.
mkdir -p app/src/main/java/io/github/jqssun/displayextend app/src/main/aidl app/src/main/res
cp -R "$TMP/extend/app/src/main/java/io/github/jqssun/displayextend/." app/src/main/java/io/github/jqssun/displayextend/
if [ -d "$TMP/extend/app/src/main/aidl" ]; then cp -R "$TMP/extend/app/src/main/aidl/." app/src/main/aidl/; fi
# Keep Saku's existing resources where names collide; add Extend-only resources.
cp -Rn "$TMP/extend/app/src/main/res/." app/src/main/res/
# Merge string resources instead of replacing Saku's strings.xml.
python3 - <<'PY'
import xml.etree.ElementTree as ET
from pathlib import Path
src=Path('/tmp/extend-not-used')
# The source path is passed by the shell below through environment.
import os
source=Path(os.environ['EXTEND_SOURCE'])/'app/src/main/res/values/strings.xml'
target=Path('app/src/main/res/values/strings.xml')
if source.exists():
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        dst=ET.parse(target); root=dst.getroot()
    else:
        root=ET.Element('resources')
    incoming=ET.parse(source).getroot()
    names={e.attrib.get('name') for e in root}
    for e in incoming:
        if e.attrib.get('name') not in names:
            root.append(e)
    ET.indent(root, space='    ')
    ET.ElementTree(root).write(target, encoding='utf-8', xml_declaration=True)
PY
