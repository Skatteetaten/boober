#!/usr/bin/env bash

which git || ( echo "Please install git" && sudo apt-get install git)


GIT_FOLDER=/tmp/boobergit/paas
BOOBER_CHECKOUT_FOLDER=/tmp/boober/paas

rm -rf $GIT_FOLDER
rm -rf $BOOBER_CHECKOUT_FOLDER

[[ -d $GIT_FOLDER ]] || (mkdir -p $GIT_FOLDER && git init --bare $GIT_FOLDER)
