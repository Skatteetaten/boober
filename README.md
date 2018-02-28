# Boober
<img align="right" src="https://vignette.wikia.nocookie.net/muppet/images/d/da/Boober_Fraggle.jpg/revision/latest/scale-to-width-down/280?cb=20121231172124">

Boober is our take on how to handle the `wall of yaml` challenge of Kubernetes. It reads configuration files with a given
schemaVersion from a git repo (AuroraConfig) and transforms it into Openshift Objects via a AuroraDeploymentConfiguration.

The component is named after the Boober Fraggle (http://muppet.wikia.com/wiki/Boober_Fraggle). 


## How to run locally

### Setup the API

Boober reads AuroraConfig and Vaults from git repositories. When running locally the standard setup is to use a seperate
project in bitbucket suffixed by `-dev`.
 
By default, boober during development will deploy to the qa cluster.

### Setup ao

To use the ```ao``` command line utility to perform actions against your local api add the ```--localhost``` flag to
the login command. Use the ```paas``` affiliation (since that is currently the only affiliation that has any test
data)

    ao login paas --localhost


## Architecture

Boober uses Git as a storage mechanism for its config files. Boober owns this repository and it should be the only
component writing to it. It will ensure that the files written are valid and that secrets are encrypted.

### How AuroraConfig is saved into git
![save](docs/images/boober.png "Save AuroraConfig")


### How objects in OpenShift are created
![deploy](docs/images/boober-deploy.png "Deploy application")

## Concepts

### Affiliation
An affiliation is a group of projects and environments that are managed by the same team. Each affiliation has a
seperate git repo where their configuration is stored.

All projects created in openshift start with the name of the affiliation.

The affiliation project (has the same name as affiliation) is set up when a project has its Onboarding.

### AuroraConfig
A set of versioned files (all files in one affiliation must have the same version) to express how to create projects
and deploy applications on OpenShift

filename           | name          | description  
-------------------|---------------|:-----------------------------------------------------------------
about.json         | global        | Global configuration for all applications in an affiliation
utv/about.json     | environment   | Environment configuration that is shared by all applications in an openshift project
reference.json     | application   | Configuration shared by all instances of an application in all projects
utv/reference.json | override      | Configuration specific to one application in one openshift project

AuroraConfig is at the moment only documented internally but will be available externally soon.

