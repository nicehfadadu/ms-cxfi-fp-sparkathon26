#!/bin/bash
# Deploy the Sparkathon transcript microservice to EC2.
#
# Uploads the prebuilt jar from target/ to S3, then launches an EC2 instance
# that bootstraps itself via user-data.sh (installs Java 17, pulls the jar
# using its instance role, and runs it as a systemd service on port 80).
#
# Prereqs:
#   - Valid AWS creds in the named profile (aws-azure-login --profile "$AWS_PROFILE").
#   - A built jar in target/ (mvn package, or reuse the existing one).
#   - Instance profile, security group, subnet, and AMI already provisioned.
set -euo pipefail

AWS_PROFILE="${AWS_PROFILE:-sparkathon}"
AWS_REGION="${AWS_REGION:-us-east-1}"

# Artifact
JAR_LOCAL="${JAR_LOCAL:-target/ms-cxfi-fp-sparkathon26-1.0.0-SNAPSHOT.jar}"
BUCKET="${BUCKET:-fi-sparkathon-d6866}"
JAR_KEY="${JAR_KEY:-package/app.jar}"

# Launch inputs (override via env as needed)
AMI_ID="${AMI_ID:-ami-0fd6240f599091088}"        # Amazon Linux 2023, x86_64
INSTANCE_TYPE="${INSTANCE_TYPE:-t3.small}"
SUBNET_ID="${SUBNET_ID:-subnet-06e73c00cdb21c5d0}"
SECURITY_GROUP_ID="${SECURITY_GROUP_ID:-sg-0ad193348e699aba2}"
INSTANCE_PROFILE="${INSTANCE_PROFILE:-sparkathon-ec2-s3-profile}"
OWNER_TAG="${OWNER_TAG:-Rushabh.Pawar}"
NAME_TAG="${NAME_TAG:-sparkathon-d6866-transcript}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AWS=(aws --profile "$AWS_PROFILE" --region "$AWS_REGION")

echo ">> Verifying credentials..."
"${AWS[@]}" sts get-caller-identity --query Arn --output text

echo ">> Uploading jar to s3://$BUCKET/$JAR_KEY ..."
[ -f "$JAR_LOCAL" ] || { echo "Jar not found: $JAR_LOCAL"; exit 1; }
"${AWS[@]}" s3 cp "$JAR_LOCAL" "s3://$BUCKET/$JAR_KEY"

echo ">> Launching EC2 instance..."
# NOTE: the org SCP requires the Owner tag on the EBS volume too, not just the instance.
INSTANCE_ID=$("${AWS[@]}" ec2 run-instances \
  --image-id "$AMI_ID" \
  --instance-type "$INSTANCE_TYPE" \
  --subnet-id "$SUBNET_ID" \
  --security-group-ids "$SECURITY_GROUP_ID" \
  --iam-instance-profile "Name=$INSTANCE_PROFILE" \
  --associate-public-ip-address \
  --user-data "file://$SCRIPT_DIR/user-data.sh" \
  --tag-specifications \
    "ResourceType=instance,Tags=[{Key=Owner,Value=$OWNER_TAG},{Key=Name,Value=$NAME_TAG}]" \
    "ResourceType=volume,Tags=[{Key=Owner,Value=$OWNER_TAG}]" \
  --query 'Instances[0].InstanceId' --output text)
echo "   InstanceId: $INSTANCE_ID"

echo ">> Waiting for instance to run..."
"${AWS[@]}" ec2 wait instance-running --instance-ids "$INSTANCE_ID"

PUBLIC_DNS=$("${AWS[@]}" ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicDnsName' --output text)

echo ">> Waiting for the app to come up (bootstrap installs Java + starts service)..."
HEALTH_URL="http://$PUBLIC_DNS/health"
until curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTH_URL" 2>/dev/null \
      | grep -q '200'; do
  sleep 10
done

GENERATE_URL="http://$PUBLIC_DNS/sparkathon/transcript/generate"
echo ""
echo "Deployed. Health:   $HEALTH_URL"
echo "          Endpoint: $GENERATE_URL"
echo ""
echo "Sample request:"
echo "  curl -X POST $GENERATE_URL \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"topic\":\"billing\",\"temperament\":\"Frustrated\"}'"
