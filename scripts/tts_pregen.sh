#!/usr/bin/env bash
set -euo pipefail

# Bulk-pregenerate the TTS corpus onto SuperGrok subscription quota.
#
#   ./scripts/tts_pregen.sh                 # dry run — plan only, spends nothing
#   ./scripts/tts_pregen.sh --live          # generate
#   ./scripts/tts_pregen.sh --live --limit 3   # smoke test a few clips first
#
# Bulk generation is OAUTH_ONLY in TtsService: no SuperGrok token means no
# generation, and an exhausted subscription aborts the run rather than falling
# back to the metered XAI_API_KEY. Nothing here can turn that off.
#
# WHY THE PERSIST FILE MATTERS MORE THAN IT LOOKS
# xAI rotates the refresh token on every use. The token printed by
# xai_oauth_login.sh is a one-shot seed: the first successful refresh consumes it
# and issues a replacement. If that replacement cannot be written to disk, it is
# lost and the seed is already dead — you would have to re-run the login script.
# The app's default path is /data/xai-oauth-refresh-token, which exists in the
# production container and NOT on a Mac. So this script always passes an explicit,
# writable path. Do not run the pregen without one.

cd "$(dirname "$0")/.."

REFRESH_TOKEN_FILE="${XAI_OAUTH_REFRESH_TOKEN_FILE:-$HOME/.rkj-xai-refresh-token}"
PROVIDER="${TTS_PROVIDER:-xai}"
VOICE="${TTS_VOICE:-helios}"
NAMESPACE="audio/${PROVIDER}/${VOICE}"
SCOPE="${TTS_PREGEN_SCOPE:-all}"
THREADS="${TTS_PREGEN_THREADS:-8}"
PORT="${TTS_PREGEN_PORT:-8083}"

DRY_RUN=true
LIMIT=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --live)  DRY_RUN=false; shift ;;
    --limit) LIMIT="$2"; shift 2 ;;
    --scope) SCOPE="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "${XAI_OAUTH_REFRESH_TOKEN:-}" && ! -f "$REFRESH_TOKEN_FILE" ]]; then
  cat >&2 <<EOF
No SuperGrok refresh token.

Mint one, then export it for this shell (do not commit it, do not paste it anywhere):

  ./scripts/xai_oauth_login.sh
  export XAI_OAUTH_REFRESH_TOKEN='<the token it prints>'

After the first successful run the rotated token lives in
$REFRESH_TOKEN_FILE and the env var is no longer needed.
EOF
  exit 1
fi

mkdir -p "$(dirname "$REFRESH_TOKEN_FILE")"

echo "namespace : $NAMESPACE"
echo "scope     : $SCOPE"
echo "token file: $REFRESH_TOKEN_FILE"
echo "mode      : $([[ "$DRY_RUN" == true ]] && echo 'DRY RUN (nothing generated)' || echo 'LIVE — subscription quota')"
[[ "$LIMIT" != 0 ]] && echo "limit     : $LIMIT clips"
echo

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
export TTS_PROVIDER="$PROVIDER"
export TTS_VOICE="$VOICE"

exec mvn -o spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dtts.max-concurrent-generations=${THREADS}" \
  -Dspring-boot.run.arguments="\
--tts.pregen.enabled=true \
--tts.pregen.dry-run=${DRY_RUN} \
--tts.pregen.scope=${SCOPE} \
--tts.pregen.threads=${THREADS} \
--tts.pregen.limit=${LIMIT} \
--tts.pregen.confirm-namespace=${NAMESPACE} \
--ai.xai.oauth.refresh-token-file=${REFRESH_TOKEN_FILE} \
--server.port=${PORT}"
