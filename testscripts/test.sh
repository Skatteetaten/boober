#!/usr/bin/env bash
oc whoami -t || (echo "You must be logged into openshift" && exit 1)

which http || ( echo "Please install httpie" && sudo apt-get install httpie)

token=$(oc whoami -t)

echo "Create test Secret Vault"
http --timeout 300 PUT :8080/affiliation/paas/secrets Authorization:"bearer $token"  < secretVault_test.json
echo "Create utv Secret Vault"

http --timeout 300 PUT :8080/affiliation/paas/secrets Authorization:"bearer $token"  < secretVault_utv.json

echo "Add aurora config for referance app"
http --timeout 300 PUT :8080/affiliation/paas/auroraconfig Authorization:"bearer $token"  < reference.json

echo "Deploy application to paas-boober-dev and paas-boober-test"
http --timeout 300 PUT :8080/affiliation/paas/deploy Authorization:"bearer $token"  < deploy.json

