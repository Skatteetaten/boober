#!/usr/bin/env bash
oc whoami -t || (echo "You must be logged into openshift" && exit 1)
which git || ( echo "Please install git" && sudo apt-get install git)
which http || ( echo "Please install httpie" && sudo apt-get install httpie)

token=$(oc whoami -t)
affiliation=$1
deployId=$2

http --timeout 300 GET :8080/v1/apply-result/$affiliation/$deployId Authorization:"bearer $token"  > apply_result_$2.json

