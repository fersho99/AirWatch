#!/usr/bin/env bash
# Compila la logica pura del reloj (sin Android) y corre las pruebas con senales sinteticas.
# Requiere kotlinc en el PATH (o KOTLINC=/ruta/a/kotlinc) y un JDK.
set -euo pipefail
cd "$(dirname "$0")"
SRC=app/src/main/java/com/airwatch/wear
OUT="${TMPDIR:-/tmp}/airwatch-logic-tests"
mkdir -p "$OUT"
"${KOTLINC:-kotlinc}" "$SRC/Settings.kt" "$SRC/MotionProcessor.kt" "$SRC/Gestures.kt" "$SRC/Calibrator.kt" "$SRC/Presentation.kt" \
  logic-tests/LogicTest.kt -include-runtime -d "$OUT/logic.jar" -nowarn
java -Dstdout.encoding=UTF-8 -jar "$OUT/logic.jar"
