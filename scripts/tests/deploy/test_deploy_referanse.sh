#!/usr/bin/env bash

token=$(oc whoami -t)
http --timeout 300 PUT :8080/affiliation/paas/deploy Authorization:"bearer $token"  < deployReferanse.json
