#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
FILES_DIR=$SCRIPT_DIR/files
GIT_FOLDER=/tmp/boobergit/paas

oc whoami -t || (echo "You must be logged into openshift" && exit 1)
which http || ( echo "Please install httpie" && sudo apt-get install httpie)

TOKEN=$(oc whoami -t)

echo "Create test Secret Vault"
#http --timeout 300 PUT :8080/v1/vault/paas Authorization:"bearer $TOKEN"  < ${FILES_DIR}/secretVault_test.json

echo "Create utv Secret Vault"
#http --timeout 300 PUT :8080/v1/vault/paas Authorization:"bearer $TOKEN"  < ${FILES_DIR}/secretVault_utv.json

echo "Add aurora config for referance app"
http --timeout 300 PUT :8080/v1/auroraconfig/paas Authorization:"bearer $TOKEN"  < ${FILES_DIR}/reference.json
#
#echo "The vaults are"
#http --timeout 300 GET :8080/affiliation/paas/vault Authorization:"bearer $TOKEN"
#
#echo "Dry run deploy of  application to paas-boober-dev and paas-boober-test"
#http --timeout 300 PUT :8080/affiliation/paas/deploy/dryrun Authorization:"bearer $TOKEN"  < ${FILES_DIR}/deploy.json
