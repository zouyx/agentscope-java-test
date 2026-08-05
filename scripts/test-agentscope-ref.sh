#!/usr/bin/env bash

# Build an AgentScope Java ref locally, then run this repository's user-facing E2E suite against it.
#
# Examples:
#   AGENTSCOPE_REF=main ./scripts/test-agentscope-ref.sh
#   AGENTSCOPE_REF=feature/my-change ./scripts/test-agentscope-ref.sh
#   AGENTSCOPE_SOURCE_DIR=../agentscope-java ./scripts/test-agentscope-ref.sh

set -euo pipefail

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly AGENTSCOPE_REPOSITORY="${AGENTSCOPE_REPOSITORY:-https://github.com/agentscope-ai/agentscope-java.git}"
readonly AGENTSCOPE_REF="${AGENTSCOPE_REF:-main}"
readonly MAVEN_REPOSITORY="${MAVEN_REPOSITORY:-${PROJECT_ROOT}/target/agentscope-m2}"

if [[ -n "${AGENTSCOPE_SOURCE_DIR:-}" ]]; then
    AGENTSCOPE_SOURCE_DIR="$(cd "${AGENTSCOPE_SOURCE_DIR}" && pwd)"
    if [[ ! -f "${AGENTSCOPE_SOURCE_DIR}/pom.xml" ]]; then
        echo "AGENTSCOPE_SOURCE_DIR must point to an AgentScope Java checkout: ${AGENTSCOPE_SOURCE_DIR}" >&2
        exit 2
    fi
    cleanup_source=false
else
    AGENTSCOPE_SOURCE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/agentscope-java.XXXXXX")"
    cleanup_source=true
fi

cleanup() {
    if [[ "${cleanup_source}" == true ]]; then
        rm -rf "${AGENTSCOPE_SOURCE_DIR}"
    fi
}
trap cleanup EXIT

if [[ "${cleanup_source}" == true ]]; then
    echo "Checking out AgentScope Java ref '${AGENTSCOPE_REF}' from ${AGENTSCOPE_REPOSITORY}"
    git -C "${AGENTSCOPE_SOURCE_DIR}" init --quiet
    git -C "${AGENTSCOPE_SOURCE_DIR}" remote add origin "${AGENTSCOPE_REPOSITORY}"
    git -C "${AGENTSCOPE_SOURCE_DIR}" fetch --depth 1 origin "${AGENTSCOPE_REF}"
    git -C "${AGENTSCOPE_SOURCE_DIR}" checkout --detach --quiet FETCH_HEAD
fi

echo "Installing AgentScope Java modules into ${MAVEN_REPOSITORY}"
mvn -B -Dmaven.repo.local="${MAVEN_REPOSITORY}" \
    -pl :agentscope-core,:agentscope-extensions-model-ollama -am \
    -DskipTests install \
    -f "${AGENTSCOPE_SOURCE_DIR}/pom.xml"

AGENTSCOPE_VERSION="$(mvn -q -DforceStdout -Dmaven.repo.local="${MAVEN_REPOSITORY}" \
    -f "${AGENTSCOPE_SOURCE_DIR}/pom.xml" \
    help:evaluate -Dexpression=project.version | tr -d '\r\n')"
if [[ -z "${AGENTSCOPE_VERSION}" || "${AGENTSCOPE_VERSION}" == *'${'* || "${AGENTSCOPE_VERSION}" == *'null object or invalid expression'* ]]; then
    echo "Unable to determine the AgentScope Java Maven version from ${AGENTSCOPE_SOURCE_DIR}/pom.xml" >&2
    exit 2
fi

echo "Running E2E tests against AgentScope Java ${AGENTSCOPE_VERSION}"
mvn -B -Dmaven.repo.local="${MAVEN_REPOSITORY}" \
    -Dagentscope.version="${AGENTSCOPE_VERSION}" \
    -pl agentscope-e2e-tests -am verify -Pe2e \
    -f "${PROJECT_ROOT}/pom.xml"
