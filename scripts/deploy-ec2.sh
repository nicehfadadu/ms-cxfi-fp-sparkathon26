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
# Org policy: instances in us-east-1 must run in VPC_Type_1 (vpc-089712d808f095552),
# which has only private subnets. No public IP is assigned; reach the app over the
# corporate VPN (traffic enters via Transit Gateway tgw-0860e58e2316c2bfc) and manage
# the box via SSM Session Manager. S3/DynamoDB/Bedrock are reached via VPC endpoints.
AMI_ID="${AMI_ID:-ami-0fd6240f599091088}"        # Amazon Linux 2023, x86_64
INSTANCE_TYPE="${INSTANCE_TYPE:-t3.small}"
SUBNET_ID="${SUBNET_ID:-subnet-0b1ec53748560427e}"   # VPC_Type_1 Subnet_A (us-east-1a, private)
SECURITY_GROUP_ID="${SECURITY_GROUP_ID:-sg-054e502fa9dc56e53}"  # VPC_Type_1, port 80 scoped to 10.0.0.0/8
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
# Private subnet: no public IP. Owner tag required on the volume too (org SCP).
INSTANCE_ID=$("${AWS[@]}" ec2 run-instances \
  --image-id "$AMI_ID" \
  --instance-type "$INSTANCE_TYPE" \
  --subnet-id "$SUBNET_ID" \
  --security-group-ids "$SECURITY_GROUP_ID" \
  --iam-instance-profile "Name=$INSTANCE_PROFILE" \
  --no-associate-public-ip-address \
  --user-data "file://$SCRIPT_DIR/user-data.sh" \
  --tag-specifications \
    "ResourceType=instance,Tags=[{Key=Owner,Value=$OWNER_TAG},{Key=Name,Value=$NAME_TAG}]" \
    "ResourceType=volume,Tags=[{Key=Owner,Value=$OWNER_TAG}]" \
  --query 'Instances[0].InstanceId' --output text)
echo "   InstanceId: $INSTANCE_ID"

echo ">> Waiting for instance to run..."
"${AWS[@]}" ec2 wait instance-running --instance-ids "$INSTANCE_ID"

PRIVATE_IP=$("${AWS[@]}" ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PrivateIpAddress' --output text)

echo ">> Waiting for the app to come up (bootstrap installs Java + starts service)..."
echo "   The instance has no public IP. Health is checked against the private IP;"
echo "   this only succeeds if you are on the corporate VPN. If not, verify via SSM:"
echo "     aws --profile $AWS_PROFILE --region $AWS_REGION ssm start-session --target $INSTANCE_ID"
echo "     # then on the box:  curl -s http://localhost:80/health"
HEALTH_URL="http://$PRIVATE_IP/health"
for i in $(seq 1 30); do
  if curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTH_URL" 2>/dev/null | grep -q '200'; then
    echo "   health OK via VPN"
    break
  fi
  sleep 10
done

GENERATE_URL="http://$PRIVATE_IP/sparkathon/transcript/generate"
echo ""
echo "Deployed to VPC_Type_1 (private). InstanceId: $INSTANCE_ID  PrivateIP: $PRIVATE_IP"
echo "  Health:   $HEALTH_URL   (VPN only)"
echo "  Endpoint: $GENERATE_URL (VPN only)"
echo ""
echo "Sample request (run on VPN, or from the box via SSM against localhost):"
echo "  curl -X POST $GENERATE_URL \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"topic\":\"billing\",\"temperament\":\"Frustrated\"}'"
