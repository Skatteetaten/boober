#!/usr/bin/env bash

token=$(oc whoami -t)
http --timeout 300 PUT :8080/v1/apply/paas Authorization:"bearer $token"  < deployReferanse.json
