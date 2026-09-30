#!/usr/bin/env bash
#
# Called by the Jenkinsfile. Asks AWS Systems Manager (SSM) to run the deployment on the EC2 host,
# waits for it to finish, prints the remote output into the Jenkins log, and fails if it failed.
#
# Why SSM instead of SSH: the instance needs no open SSH port and no SSH keys; access is controlled
# entirely by IAM, and every command is recorded in AWS (Systems Manager > Run Command history).
#
# Required environment: AWS_REGION, BI_INSTANCE_ID, RELEASE_SHA, plus AWS credentials.

set -euo pipefail

: "${AWS_REGION:?}" "${BI_INSTANCE_ID:?}" "${RELEASE_SHA:?}"

# RELEASE_SHA can come from a Jenkins build parameter and is embedded in a remote shell command,
# so accept nothing but a full hexadecimal commit SHA (prevents command injection).
if [[ ! "$RELEASE_SHA" =~ ^[0-9a-f]{40}$ ]]; then
    echo "RELEASE_SHA must be a full 40-character commit SHA, got '$RELEASE_SHA'" >&2
    exit 2
fi

# Runs on the instance as root; drop to ec2-user, move the checkout to the release commit
# (so compose files and migrations match the image), then run the deploy script from that commit.
REMOTE_COMMAND="sudo -u ec2-user -H bash -lc 'set -e; cd /opt/bi/app; git fetch --quiet origin; git checkout --quiet --force $RELEASE_SHA; ./deploy/deploy.sh $RELEASE_SHA'"

# jq builds the JSON so quoting inside REMOTE_COMMAND cannot break the request.
PARAMETERS="$(jq -n --arg cmd "$REMOTE_COMMAND" '{commands: [$cmd]}')"

COMMAND_ID="$(aws ssm send-command \
    --region "$AWS_REGION" \
    --instance-ids "$BI_INSTANCE_ID" \
    --document-name AWS-RunShellScript \
    --comment "bi-backend deploy ${RELEASE_SHA:0:7}" \
    --timeout-seconds 900 \
    --parameters "$PARAMETERS" \
    --query Command.CommandId \
    --output text)"

echo "SSM command $COMMAND_ID sent to $BI_INSTANCE_ID; waiting for it to finish..."

while true; do
    # Right after sending, the invocation may not exist yet; treat that as still pending.
    STATUS="$(aws ssm get-command-invocation --region "$AWS_REGION" \
        --command-id "$COMMAND_ID" --instance-id "$BI_INSTANCE_ID" \
        --query Status --output text 2>/dev/null || echo Pending)"
    case "$STATUS" in
        Success|Failed|Cancelled|TimedOut) break ;;
        *) sleep 5 ;;
    esac
done

echo "----- remote output (SSM keeps the first 24,000 characters) -----"
aws ssm get-command-invocation --region "$AWS_REGION" \
    --command-id "$COMMAND_ID" --instance-id "$BI_INSTANCE_ID" \
    --query '[StandardOutputContent, StandardErrorContent]' --output text
echo "------------------------------------------------------------------"

if [[ "$STATUS" != "Success" ]]; then
    echo "Remote deployment finished with status: $STATUS" >&2
    exit 1
fi
echo "Remote deployment succeeded."
