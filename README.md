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


Boober contains the followin main parts

### AuroraConfig
A set of json/yaml files stored in a git repository under a single project/organization. Boober needs commit access to it.
Users can directly push to the AuroraConfig repository, in order to ensure that files are validated Boober provides a 
validation endpoint

Boober itself can modify single files in the AuroraConfig. For more complex operations on AuroraConfig just use git directly.

When Boober does a deploy a deployTag is created in the AuroraConfig repository.

AuroraConfig is versioned with a schemaVersion. All files in a single AuroraConfig must have the same schemaVersion.

The following file types are in an AuroraConfig

filename           | name          | description  
-------------------|---------------|:-----------------------------------------------------------------
about.json         | global        | Global configuration for all applications in an affiliation
utv/about.json     | environment   | Environment configuration that is shared by all applications in an openshift project
reference.json     | application   | Configuration shared by all instances of an application in all projects
utv/reference.json | override      | Configuration specific to one application in one openshift project

AuroraConfig is at the moment only documented internally but will be available externally soon.

### AuroraVaults
Secrets are stored in another git repo encrypted. Boober is the only one who should write/read from this repo since
it has the encryption keys.


### AuroraDeploymentSpec
An unversioned abstraction between AuroraConfig and the ObjectGeneration processes via ApplicationId.

Given an applicationId that is deployed from an AuroraConfig the following process will happen:
 
 - find the files in an AuroraConfig that is relevant for this ApplicationId
 - convert the AuroraConfig into an AuroraDeploymentSpec using a versioned process
 - the mapping logic will retain information about what file a given config item is retrieved from
  
### External resources provisioning
External resources that are needed for an application to start is processed in Boober and given to the ObjectGeneration 
process

### OpenShiftObjectGeneration
Boober user [kubernetes-model](https://github.com/fabric8io/kubernetes-model) and [kubernetes-dsl](https://github.com/fkorotkov/k8s-kotlin-dsl) in order to transfer input into OpenShift objects.

### ObjectObjectUpdate
When creating/updating state in OpenShift Boober has a very opinoinated approach. We do not use apply on the objects we
put/update the new objects into the cluster conditionally on resourceVersion. 

We do this because we want the truth of the state to be in AuroraConfig not in the cluster. If somebody fiddles with an
object and want it back to desired state they only need to run the deploy process in Boober.

Immutable fields on objects are retained.



