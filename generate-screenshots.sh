#!/usr/bin/env bash
# Génère les captures d'écran fastlane (fastlane/metadata/android/<locale>/images/),
# converties en WebP, puis une vidéo de démo (screenshots/demo.mp4 + demo.gif).
#
# Captures : UN seul test instrumenté (StoreScreenshotsTest), dans le processus
# de l'app, animations système coupées ; les PNG reviennent en un flux tar puis
# `image-converter.bin --inplace .` les convertit dans fastlane/.
# Vidéo : DemoVideoTest rejoue un parcours en temps réel (animations rétablies)
# sous `screenrecord` ; ffmpeg produit le mp4 et le gif.
#
# Usage : ./generate-screenshots.sh [options]
#   -s, --serial SERIAL   appareil adb (défaut : $ANDROID_SERIAL, sinon le
#                         premier émulateur lancé, sinon démarre l'AVD)
#   -l, --locales "..."   langues (défaut : "en-US fr-FR zh-CN")
#   -f, --formats "..."   formats parmi phone, sevenInch, tenInch (défaut : tous)
#   -i, --install         force la réinstallation de l'APK debug et de l'APK de test
#       --no-video        captures seules
#       --video-only      vidéo seule
#       --video-locale L  langue de la vidéo (défaut : en-US)
#       --avd NOM         AVD démarré si aucun appareil n'est disponible (défaut : Pixel_6a)
#   -h, --help            cette aide
#
# Les APK sont reconstruits à chaque lancement (Gradle incrémental) et
# réinstallés seulement s'ils ont changé depuis la dernière installation.
# Prérequis : adb, tar, image-converter.bin (captures), ffmpeg (vidéo) ;
# build debug (données d'exemple, pas de FLAG_SECURE).
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG="fr.gildasledrogoff.tokenusage"
TEST_PKG="${PKG}.test"
TEST_CLASS="${PKG}.screenshots.StoreScreenshotsTest"
VIDEO_CLASS="${PKG}.screenshots.DemoVideoTest"
RUNNER="${TEST_PKG}/androidx.test.runner.AndroidJUnitRunner"
DEVICE_DIR="files/store-screenshots"
FASTLANE_DIR="${PROJECT_DIR}/fastlane"
METADATA_DIR="${FASTLANE_DIR}/metadata/android"
VIDEO_DIR="${PROJECT_DIR}/screenshots"
DEVICE_VIDEO="/data/local/tmp/tokenusage-demo.mp4"
SCREENSHOTS=1
VIDEO=0
VIDEO_LOCALE="en-US"
VIDEO_FORMAT="phone"
GIF_WIDTH=280
GIF_FPS=10
SERIAL="${ANDROID_SERIAL:-}"
LOCALES="en-US fr-FR zh-CN"
FORMATS="phone sevenInch tenInch"
INSTALL=0
AVD="${AVD:-Pixel_6a}"
AVD_PORT=5560
EMULATOR_FLAGS="${EMULATOR_FLAGS:--no-audio -no-boot-anim -no-snapshot -gpu swiftshader -memory 6000 -cores 7 -netdelay none -netspeed full -accel on -feature -Vulkan}"

die() {
	echo "Erreur : $*" >&2
	exit 1
}
info() { echo "$*" >&2; }

while [ $# -gt 0 ]; do
	case "$1" in
	-s | --serial)
		SERIAL="${2:?valeur manquante}"
		shift 2
		;;
	-l | --locales)
		LOCALES="${2:?valeur manquante}"
		shift 2
		;;
	-f | --formats)
		FORMATS="${2:?valeur manquante}"
		shift 2
		;;
	-i | --install)
		INSTALL=1
		shift
		;;
	--no-video)
		VIDEO=0
		shift
		;;
	--video-only)
		SCREENSHOTS=0
		shift
		;;
	--video-locale)
		VIDEO_LOCALE="${2:?valeur manquante}"
		shift 2
		;;
	--fast) shift ;; # ancien mode multi-émulateurs : devenu inutile, ignoré
	--avd)
		AVD="${2:?valeur manquante}"
		shift 2
		;;
	-h | --help)
		sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
		exit 0
		;;
	*) die "option inconnue : $1" ;;
	esac
done

command -v adb > /dev/null || die "adb introuvable"
[ "$SCREENSHOTS" = 1 ] || [ "$VIDEO" = 1 ] || die "--no-video et --video-only s'excluent"
if [ "$SCREENSHOTS" = 1 ]; then command -v image-converter.bin > /dev/null || die "image-converter.bin introuvable"; fi
if [ "$VIDEO" = 1 ]; then command -v ffmpeg > /dev/null || die "ffmpeg introuvable (ou --no-video)"; fi
for f in $FORMATS; do
	case "$f" in
	phone | sevenInch | tenInch) ;;
	*) die "format inconnu : $f (phone, sevenInch, tenInch)" ;;
	esac
done

adb_() { adb -s "$SERIAL" "$@"; }
sh_() { adb_ shell "$@" < /dev/null; }

# --- Appareil ---------------------------------------------------------------

STARTED_EMULATOR=""
start_emulator() {
	local emulator try
	emulator="$(command -v emulator || true)"
	[ -n "$emulator" ] || emulator="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}/emulator/emulator"
	[ -x "$emulator" ] || die "aucun appareil adb et emulator introuvable (PATH ou ANDROID_HOME)"
	SERIAL="emulator-$AVD_PORT"
	EMULATOR_LOG="$(mktemp)"
	info "Aucun appareil : démarrage de $SERIAL ($AVD)…"
	# shellcheck disable=SC2086
	"$emulator" -avd "$AVD" -read-only -port "$AVD_PORT" $EMULATOR_FLAGS > "$EMULATOR_LOG" 2>&1 &
	STARTED_EMULATOR="$SERIAL"
	for try in $(seq 1 300); do
		if [ "$(sh_ getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = "1" ] &&
			sh_ pm path android > /dev/null 2>&1; then
			return 0
		fi
		sleep 1
	done
	die "$SERIAL n'a pas démarré (journal : $EMULATOR_LOG)"
}

stop_emulator() {
	[ -n "$STARTED_EMULATOR" ] || return 0
	info "Arrêt de $STARTED_EMULATOR…"
	adb -s "$STARTED_EMULATOR" emu kill > /dev/null 2>&1 || true
	wait 2> /dev/null || true
}
trap stop_emulator EXIT
trap 'exit 130' INT TERM

if [ -z "$SERIAL" ]; then
	SERIAL="$(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')"
	[ -n "$SERIAL" ] || start_emulator
fi
[ "$(adb_ get-state 2> /dev/null | tr -d '\r')" = "device" ] ||
	die "appareil '$SERIAL' indisponible (adb devices)"

# --- APK --------------------------------------------------------------------

# Réinstalle un APK seulement s'il diffère de celui posé au dernier passage
# (empreinte mémorisée sur l'appareil : elle disparaît avec lui).
install_apk() {
	local pkg="$1" apk="$2" stamp sum
	[ -f "$apk" ] || die "APK absent : $apk"
	stamp="/data/local/tmp/${pkg}.sha256"
	sum="$(sha256sum "$apk" | cut -d' ' -f1)"
	if [ "$INSTALL" = 0 ] && [ -n "$(sh_ pm path "$pkg" 2> /dev/null)" ] &&
		[ "$(sh_ cat "$stamp" 2> /dev/null | tr -d '\r')" = "$sum" ]; then
		return 0
	fi
	info "Installation de ${apk##*/}…"
	adb_ install -r "$apk" > /dev/null
	sh_ "echo $sum > $stamp"
}

info "Construction des APK (debug + test)…"
(cd "$PROJECT_DIR" && ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest) >&2
install_apk "$PKG" "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
install_apk "$TEST_PKG" "$PROJECT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

sh_ dumpsys package "$PKG" | grep -q "DEBUGGABLE" ||
	die "build release installé : FLAG_SECURE (captures noires) et aucune donnée d'exemple. Relancer avec -i"
# Bascule « Données d'exemple » (préférence en clair, lisible en debug).
if sh_ "run-as $PKG cat shared_prefs/tokenusage_debug_demo.xml 2>/dev/null" |
	grep -q 'name="demo_enabled" value="false"'; then
	die "données d'exemple désactivées dans les réglages de l'app : les réactiver"
fi

# --- État de l'appareil : sauvegarde / restauration -------------------------

saved_night="$(sh_ cmd uimode night | tr -d '\r' | sed 's/.*: //')"
saved_locales="$(sh_ cmd locale get-app-locales "$PKG" --user 0 | tr -d '\r' | sed 's/.*\[\(.*\)\].*/\1/')"
if sh_ dumpsys deviceidle whitelist | grep -q ",$PKG,"; then was_whitelisted=1; else was_whitelisted=0; fi
ANIMATION_SCALES="window_animation_scale transition_animation_scale animator_duration_scale"
saved_scales=""
for key in $ANIMATION_SCALES; do
	saved_scales+="$(sh_ settings get global "$key" | tr -d '\r') "
done

# Échelle des animations système : 0 pour des captures immédiates et stables,
# 1 pour la vidéo.
set_animations() {
	local key
	for key in $ANIMATION_SCALES; do sh_ settings put global "$key" "$1"; done
}

restore_animations() {
	local key value i=1
	for key in $ANIMATION_SCALES; do
		value="$(cut -d' ' -f"$i" <<< "$saved_scales")"
		i=$((i + 1))
		if [ -z "$value" ] || [ "$value" = "null" ]; then
			sh_ settings delete global "$key"
		else
			sh_ settings put global "$key" "$value"
		fi
	done
}

cleanup() {
	trap - EXIT INT TERM
	if [ -z "$STARTED_EMULATOR" ]; then
		info "Restauration de l'appareil…"
		{
			sh_ "am broadcast -a com.android.systemui.demo -e command exit
           wm size reset; wm density reset
           cmd uimode night ${saved_night:-no}
           cmd locale set-app-locales $PKG --user 0 --locales '${saved_locales}'
           am force-stop $PKG
           run-as $PKG rm -rf $DEVICE_DIR
           rm -f $DEVICE_VIDEO"
			restore_animations
			[ "$was_whitelisted" = 1 ] || sh_ cmd deviceidle whitelist "-$PKG"
		} > /dev/null 2>&1 || true
	fi
	stop_emulator
}
trap cleanup EXIT

# Pas de dialogue de permission ni d'invite d'optimisation de batterie.
sh_ "pm grant $PKG android.permission.POST_NOTIFICATIONS; cmd deviceidle whitelist +$PKG" > /dev/null 2>&1 || true

# Lance une classe de test ; le reste des arguments part vers `am instrument`.
run_test() {
	local class="$1" log
	shift
	log="$(mktemp)"
	sh_ am instrument -w -e class "$class" "$@" "$RUNNER" 2>&1 | tr -d '\r' | tee "$log" >&2 || true
	if grep -q '^OK (' "$log"; then
		rm -f "$log"
		return 0
	fi
	rm -f "$log"
	return 1
}

# --- Captures ---------------------------------------------------------------

if [ "$SCREENSHOTS" = 1 ]; then
	set_animations 0
	if ! run_test "$TEST_CLASS" -e locales "${LOCALES// /,}" -e formats "${FORMATS// /,}"; then
		failure="$PROJECT_DIR/screenshot-failure-$SERIAL.png"
		if adb_ exec-out run-as "$PKG" cat "$DEVICE_DIR/failure.png" > "$failure" 2> /dev/null && [ -s "$failure" ]; then
			die "le test de captures a échoué (écran au moment de l'échec : ${failure##*/})"
		fi
		rm -f "$failure"
		die "le test de captures a échoué (voir la sortie ci-dessus)"
	fi

	for locale in $LOCALES; do
		for format in $FORMATS; do
			out="$METADATA_DIR/$locale/images/${format}Screenshots"
			mkdir -p "$out"
			rm -f "$out"/[0-9]_*.png "$out"/[0-9]_*.webp
		done
	done
	adb_ exec-out run-as "$PKG" tar -c -C "$DEVICE_DIR" . | tar -x -C "$METADATA_DIR"

	info "Conversion WebP (image-converter.bin)…"
	(cd "$FASTLANE_DIR" && image-converter.bin --inplace .) >&2
	info "Captures : $(find "$METADATA_DIR" -path '*Screenshots/*' -type f | wc -l) fichiers dans ${METADATA_DIR#"$PROJECT_DIR"/}"
fi

# --- Vidéo de démo ----------------------------------------------------------

if [ "$VIDEO" = 1 ]; then
	info "== vidéo de démo ($VIDEO_LOCALE, $VIDEO_FORMAT)"
	set_animations 1
	run_test "$VIDEO_CLASS" -e locale "$VIDEO_LOCALE" -e format "$VIDEO_FORMAT" ||
		die "l'enregistrement de la vidéo a échoué (voir la sortie ci-dessus)"

	raw="$(mktemp --suffix=.mp4)"
	adb_ pull "$DEVICE_VIDEO" "$raw" > /dev/null || die "vidéo introuvable sur l'appareil"
	mkdir -p "$VIDEO_DIR"
	ffmpeg -v error -y -i "$raw" -an -vf "fps=30" -c:v libx264 -preset slow -crf 23 \
		-pix_fmt yuv420p -movflags +faststart "$VIDEO_DIR/demo.mp4" < /dev/null
	ffmpeg -v error -y -i "$raw" -an -filter_complex \
		"fps=$GIF_FPS,scale=$GIF_WIDTH:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
		"$VIDEO_DIR/demo.gif" < /dev/null
	rm -f "$raw"
	info "Vidéo : $(du -h "$VIDEO_DIR/demo.mp4" "$VIDEO_DIR/demo.gif" | awk '{ printf "%s (%s) ", $2, $1 }')"
fi

info "Terminé."
