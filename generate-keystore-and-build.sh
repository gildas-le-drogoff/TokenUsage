#!/bin/bash
set -euo pipefail

# JDK 25 : même version que la recette F-Droid (build reproductible).
export JAVA_HOME="/usr/lib/jvm/java-25-openjdk"
export PATH="${JAVA_HOME}/bin:${PATH}"

KEYSTORE_FILE="upload-keystore.jks"
KEYSTORE_PROPS="keystore.properties"
KEY_ALIAS="upload"
NAME="0_TokenUsage_Android"
SYNC_DIR="/mnt/NUMBER_4/Sync_Gildas_s_Phone/Orion/0_AppDev"
APK_OUT="app/build/outputs/apk"

RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
YELLOW=$'\033[1;33m'
NC=$'\033[0m'

cd "$(dirname "$(readlink -f "$0")")"

# --- Keystore d'upload (généré une seule fois) ---
if [ -f "$KEYSTORE_FILE" ] && [ ! -f "$KEYSTORE_PROPS" ]; then
	echo "${RED}${KEYSTORE_FILE} existe mais ${KEYSTORE_PROPS} est absent."
	echo "Mot de passe irrécupérable : restaurer ${KEYSTORE_PROPS} depuis une sauvegarde.${NC}"
	exit 1
elif [ ! -f "$KEYSTORE_FILE" ]; then
	password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
	keytool -genkeypair -v -keystore "$KEYSTORE_FILE" -alias "$KEY_ALIAS" \
		-keyalg RSA -keysize 5120 -validity 10950 \
		-storepass "$password" -keypass "$password" \
		-dname "CN=TokenUsage_Upload Key,O=ledrogoff,C=FR"
	(
		umask 077
		cat > "$KEYSTORE_PROPS" << EOF
storeFile=../${KEYSTORE_FILE}
storePassword=${password}
keyAlias=${KEY_ALIAS}
keyPassword=${password}
EOF
	)
	echo "${YELLOW}⚠️  Nouveau keystore d'upload généré : sauvegarder ${KEYSTORE_FILE} et ${KEYSTORE_PROPS} MAINTENANT."
	echo "⚠️  Les perdre, c'est perdre la clé d'upload Play.${NC}"
fi

# --- Build ---
./gradlew assembleDebug assembleRelease assembleStaging --console=plain

# --- Copie des APK à la racine du projet (un APK universel par build type ;
# release ne contient que les ABI ARM) ---
RELEASE_APK="${APK_OUT}/release/app-release.apk"
VERSION_NAME="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)"
cp "$RELEASE_APK" "${NAME}.apk"
# Nom attendu par le champ Binaries de la recette F-Droid (à joindre à la
# release GitHub du tag v${VERSION_NAME}).
cp "$RELEASE_APK" "TokenUsage-${VERSION_NAME}.apk"
cp "${APK_OUT}/debug/app-debug.apk" "${NAME}_debug.apk"
cp "${APK_OUT}/staging/app-staging.apk" "${NAME}_staging.apk"
echo "${GREEN}✅ APK copiés : $(echo "${NAME}"*.apk)${NC}"

# --- Envoi vers le dossier synchronisé (local sur mercury, ssh sinon) ---
DEST="${SYNC_DIR}"
[ "$(hostname -s)" = "mercury" ] || DEST="mercury:${SYNC_DIR}"
rsync -ah --info=progress2 "${NAME}.apk" "${DEST}/${NAME}_$(date +%F_%H).apk"
rsync -ah --info=progress2 "${NAME}.apk" "${DEST}/${NAME}.apk"

# --- Installation sur l'appareil ---
adb install -r "$RELEASE_APK"
