#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
FILES_DIR=$SCRIPT_DIR

token=$(oc whoami -t)

http --timeout 300 PUT :8080/affiliation/paas/deploy/dryrun Authorization:"bearer $TOKEN"  < ${FILES_DIR}/deployWebReferanse.json
http --timeout 300 PUT :8080/affiliation/paas/deploy Authorization:"bearer $token"  < ${FILES_DIR}/deployWebReferanse.json
