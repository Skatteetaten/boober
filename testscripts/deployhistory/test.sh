#!/usr/bin/env bash
oc whoami -t || (echo "You must be logged into openshift" && exit 1)
which git || ( echo "Please install git" && sudo apt-get install git)
which http || ( echo "Please install httpie" && sudo apt-get install httpie)

token=$(oc whoami -t)


echo "Deploy history for affiliation"
http --timeout 300 GET :8080/affiliation/paas/deploy Authorization:"bearer $token"  > history.json

