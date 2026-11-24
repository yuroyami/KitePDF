#!/usr/bin/env bash
# Uploads one zip to the Maven Central Portal as one deployment, then waits for the result.
# AUTOMATIC publishes the deployment after Central has checked every file in it. USER_MANAGED stops
# after the check, and the owner then publishes or drops the deployment on the Central Portal.
#
# Usage: scripts/central-upload.sh <zip> <deployment name> <AUTOMATIC|USER_MANAGED>
# Reads MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD, the two parts of a Central Portal user token.
# Tests can set MAVEN_CENTRAL_API_URL and POLL_SECONDS.
set -euo pipefail

if [ $# -ne 3 ]; then
  echo "usage: $0 <zip> <deployment name> <AUTOMATIC|USER_MANAGED>" >&2
  exit 2
fi
zip=$1
name=$2
mode=$3
case $mode in
  AUTOMATIC | USER_MANAGED) ;;
  *)
    echo "error: the publishing type is AUTOMATIC or USER_MANAGED, not $mode" >&2
    exit 2
    ;;
esac
if [ ! -f "$zip" ]; then
  echo "error: $zip does not exist" >&2
  exit 2
fi
: "${MAVEN_CENTRAL_USERNAME:?is not set}" "${MAVEN_CENTRAL_PASSWORD:?is not set}"

api=${MAVEN_CENTRAL_API_URL:-https://central.sonatype.com/api/v1}
portal=https://central.sonatype.com/publishing/deployments
token=$(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')
# GitHub hides the two secrets in the log, but not this encoded form of them.
if [ -n "${GITHUB_ACTIONS:-}" ]; then
  echo "::add-mask::$token"
fi

# Spec: https://central.sonatype.org/publish/publish-portal-api/
# The upload has no retry. A retry after a lost answer would make a second deployment.
query="name=$(jq -rn --arg v "$name" '$v | @uri')&publishingType=$mode"
if ! id=$(curl --silent --show-error --fail-with-body \
  --header "Authorization: Bearer $token" \
  --form "bundle=@$zip;type=application/octet-stream" \
  "$api/publisher/upload?$query"); then
  echo "error: the upload failed. Central answered: ${id:-nothing}" >&2
  echo "Before you upload again, look on $portal for a deployment that this upload made." >&2
  exit 1
fi
if ! [[ $id =~ ^[0-9a-fA-F-]{36}$ ]]; then
  echo "error: Central answered the upload with '$id', not a deployment ID. Look for the deployment on $portal" >&2
  exit 1
fi
echo "Uploaded $zip as deployment $id, \"$name\", $mode."

failures=0
last=
while :; do
  sleep "${POLL_SECONDS:-10}"
  state=
  if status=$(curl --silent --show-error --fail-with-body --request POST \
    --header "Authorization: Bearer $token" \
    "$api/publisher/status?id=$id"); then
    state=$(jq -r '.deploymentState // empty' <<<"$status" 2>/dev/null) || state=
  fi
  if [ -z "$state" ]; then
    # A status request changes nothing on Central, so a few failed ones in a row do no harm.
    failures=$((failures + 1))
    if [ "$failures" -ge 6 ]; then
      echo "error: 6 status requests in a row failed. Deployment $id goes on without this job; see $portal" >&2
      exit 1
    fi
    continue
  fi
  failures=0
  if [ "$state" != "$last" ]; then
    echo "$(date -u +%H:%M:%S) deployment $id: $state"
    last=$state
  fi
  case $state in
    PUBLISHED)
      echo "\"$name\" is on Maven Central."
      exit 0
      ;;
    VALIDATED)
      if [ "$mode" = USER_MANAGED ]; then
        echo "Central checked every file. Open $portal, check \"$name\", then click Publish or Drop."
        exit 0
      fi
      ;;
    FAILED)
      echo "error: Central refused deployment $id, so nothing was published. The errors:" >&2
      jq '.errors' <<<"$status" >&2
      exit 1
      ;;
  esac
done
