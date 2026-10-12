#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
chmod +x infra/localstack/init.sh
docker compose --profile observability up --build -d rcaproof-api prometheus grafana
docker compose --profile observability run --rm release-check
printf '%s\n' 'Demo passed. Default URLs: API http://localhost:8084, Grafana http://localhost:3001/d/rcaproof, Prometheus http://localhost:9091'
