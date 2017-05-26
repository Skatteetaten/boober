#!/usr/bin/env bash
oc whoami -t || (echo "You must be logged into openshift" && exit 1)
mkdir -p /tmp/boobergit/paas

which http || ( echo "Please install httpie" && sudo apt-get install httpie)

token=$(oc whoami -t)
#save the aurora config in your repo
http --timeout 300 PUT :8080/affiliation/paas/auroraconfig Authorization:"bearer $token"  < auroraconfig.json

#get your aurora config
http --timeout 300 GET :8080/affiliation/paas/auroraconfig Authorization:"bearer $token"

#deploy an application
http --timeout 300 PUT :8080/affiliation/paas/deploy Authorization:"bearer $token"  < deployReferanse.json

