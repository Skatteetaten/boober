#!/usr/bin/env bash

which git || ( echo "Please install git" && sudo apt-get install git)


GIT_FOLDER=/tmp/boobergit/paas
VAULT_FOLDER=/tmp/boobergitvault/paas
BOOBER_CHECKOUT_FOLDER=/tmp/boober/paas
BOOBER_CHECKOUT_VAULT_FOLDER=/tmp/boobervault/paas

rm -rf $GIT_FOLDER
rm -rf $VAULT_FOLDER
rm -rf $BOOBER_CHECKOUT_FOLDER
rm -rf $BOOBER_CHECKOUT_VAULT_FOLDER

[[ -d $GIT_FOLDER ]] || (mkdir -p $GIT_FOLDER && git init --bare $GIT_FOLDER)
[[ -d $VAULT_FOLDER ]] || (mkdir -p $VAULT_FOLDER && git init --bare $VAULT_FOLDER)
