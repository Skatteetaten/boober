#!/usr/bin/env bash

token=$(oc whoami -t)
http --timeout 300 PATCH \
  :8080/affiliation/paas/auroraconfigfile/refapp.json \
  Authorization:"bearer $token" \
  AuroraConfigFileVersion:abscda \
  Content-Type:"application/json-patch+json" < jsonPatchOp.json

