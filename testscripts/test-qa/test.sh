#!/usr/bin/env bash

token=$(oc whoami -t)
cluster=qa
url=http://boober-aurora.$cluster.paas.skead.no/affiliation/aurora

http --timeout 300 PUT $url/deploy/dryrun Authorization:"bearer $token"  < input.json | jq "."  > res.json
