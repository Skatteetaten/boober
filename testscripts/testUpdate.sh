#!/usr/bin/env bash

token=$(oc whoami -t)
http --timeout 300 PUT :8080/affiliation/paas/auroraconfigfile/about.json Authorization:"bearer $token"  < new-about.json

