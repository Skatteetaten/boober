#!/usr/bin/env bash

token=$(oc whoami -t)
http --timeout 300 PATCH :8080/affiliation/paas/auroraconfig/referanse.json Authorization:"bearer $token" < jsonPatchOp.json

