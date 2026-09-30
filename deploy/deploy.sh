#!/usr/bin/env bash
#
# Deploys one release of bi-backend on the EC2 host. Runs ON the server, as ec2-user.
# Jenkins invokes it through AWS Systems Manager after checking out the matching commit:
#
#   deploy/deploy.sh <40-character git commit SHA>
#
# Steps: log in to ECR -> pull the image CI built for that commit -> (re)start the stack ->
# wait for /actuator/health -> on failure, roll the backend back to the previous image.

set -euo pipefail

RELEASE_SHA="${1:?usage: deploy.sh <git-commit-sha>}"
if [[ ! "$RELEASE_SHA" =~ ^[0-9a-f]{40}$ ]]; then
    echo "Refusing to deploy: '$RELEASE_SHA' is not a full git commit SHA." >&2
    exit 2
fi

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="/opt/bi/state"
CURRENT_IMAGE_FILE="$STATE_DIR/current-image"
HEALTH_URL="http://localhost:8101/api/actuator/health"
HEALTH_TIMEOUT_SECONDS=180

log() { echo "[deploy $(date -u +%H:%M:%S)] $*"; }

compose() {
    docker compose -f "$APP_DIR/compose.yaml" -f "$APP_DIR/compose.prod.yaml" --profile app "$@"
}

wait_until_healthy() {
    local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if curl -fsS --max-time 5 "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then
            return 0
        fi
        sleep 5
    done
    return 1
}

cd "$APP_DIR"
mkdir -p "$STATE_DIR"

# The instance discovers its own region and account, so no configuration file is needed.
# IMDSv2: fetch a session token first, then use it to read instance metadata.
IMDS_TOKEN="$(curl -fsS -X PUT http://169.254.169.254/latest/api/token \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')"
AWS_REGION="$(curl -fsS -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" \
    http://169.254.169.254/latest/meta-data/placement/region)"
AWS_ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
REGISTRY="$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"

NEW_IMAGE="$REGISTRY/bi-backend:$RELEASE_SHA"
PREVIOUS_IMAGE="$(cat "$CURRENT_IMAGE_FILE" 2>/dev/null || true)"

log "Deploying $NEW_IMAGE"
log "Previous release: ${PREVIOUS_IMAGE:-<none, first deployment>}"

# The instance role grants read-only ECR access, so this login needs no stored password.
aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null

export BACKEND_IMAGE="$NEW_IMAGE"
compose pull backend

# Recreates only containers whose configuration changed; MySQL/Redis/RabbitMQ keep running.
# The migrations service runs again first, which is why every migration must be idempotent.
compose up -d --remove-orphans

log "Waiting up to ${HEALTH_TIMEOUT_SECONDS}s for $HEALTH_URL"
if wait_until_healthy; then
    echo "$NEW_IMAGE" > "$CURRENT_IMAGE_FILE"
    # Remove images no container uses any more, so old releases do not fill the disk.
    docker image prune -af --filter "until=168h" >/dev/null || true
    log "Release ${RELEASE_SHA:0:7} is healthy."
    exit 0
fi

log "Release ${RELEASE_SHA:0:7} did not become healthy. Recent backend logs:"
compose logs --tail 80 backend || true

if [[ -z "$PREVIOUS_IMAGE" ]]; then
    log "No previous release to roll back to."
    exit 1
fi

log "Rolling back to $PREVIOUS_IMAGE"
# Note: only the application image rolls back. Database migrations are forward-only, so every
# schema change must stay compatible with the previous release (add columns; never rename or drop
# in the same release that stops using them).
export BACKEND_IMAGE="$PREVIOUS_IMAGE"
compose up -d backend
if wait_until_healthy; then
    log "Rollback succeeded; the previous release is serving traffic."
else
    log "Rollback ALSO failed. Manual investigation required."
fi
exit 1
