#!/usr/bin/env bash

token=$(oc whoami -t)
#http --timeout 300 PUT :8080/affiliation/paas/setup Authorization:"bearer $token"  < files.json
#http --timeout 300 PUT :8080/affiliation/paas/auroraconfig Authorization:"bearer $token"  < import.json
http --timeout 300 GET :8080/affiliation/paas/auroraconfig Authorization:"bearer $token"
#http --timeout 300 PUT :8080/affiliation/paas/setup Authorization:"bearer $token"  < consoleSetup.json

