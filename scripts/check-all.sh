#!/usr/bin/env bash
# Run unit/integration tests and the complete migration acceptance cycle in Docker.
set -euo pipefail
exec "$(dirname "$0")/check-migrations.sh" --tests
