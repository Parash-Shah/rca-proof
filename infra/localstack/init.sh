#!/bin/bash
set -euo pipefail
awslocal sqs create-queue --queue-name payment-orders --attributes VisibilityTimeout=60
for table in orders payments; do
  awslocal dynamodb create-table --table-name "$table" \
    --attribute-definitions AttributeName=orderId,AttributeType=S \
    --key-schema AttributeName=orderId,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST
done
