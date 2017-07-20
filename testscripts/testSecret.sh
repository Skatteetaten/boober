#!/usr/bin/env bash

token=$(oc whoami -t)


echo "Posting"
#http --timeout 300 PUT :8080/affiliation/paas/secrets Authorization:"bearer $token"  < secrets.json

echo "Result"
http --timeout 300 GET :8080/affiliation/paas/secrets Authorization:"bearer $token"
