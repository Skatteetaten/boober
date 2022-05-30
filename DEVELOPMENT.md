
##Aurora features
Handlers for Aurora features are defined in `no.skatteetaten.aurora.boober.feature`.

### Features
Features must extend the interface [Feature](./src/main/kotlin/no/skatteetaten/aurora/boober/feature/Feature.kt).
The interface include documentation for the functions that needs to be implemented.

The implemented functions are used in [AuroraDeploymentContext](./src/main/kotlin/no/skatteetaten/aurora/boober/model/AuroraDeploymentContext.kt)
used by [AuroraDeploymentContextService](./src/main/kotlin/no/skatteetaten/aurora/boober/service/AuroraDeploymentContextService.kt)
which in turn is used by [DeployFacade](./src/main/kotlin/no/skatteetaten/aurora/boober/facade/DeployFacade.kt). 


The interface provides two functions for generating resources: `generate` and `generateSequentially`.
A feature will normally only need to implement the `generate` function which will be executed in parallel.
The `generateSequentially` function is intended for resources where parallel generation may result in duplicate resources (for example shared databases [DatabaseFeature](./src/main/kotlin/no/skatteetaten/aurora/boober/feature/DatabaseFeature.kt)). 

### Deployment
The handlers are used by [DeployFacade](./src/main/kotlin/no/skatteetaten/aurora/boober/facade/DeployFacade.kt)`.executeDeploy` 
and take the following arguments:

| Argument                    | Type                           | Description                                                   |
|-----------------------------|--------------------------------|---------------------------------------------------------------|
| `ref`                       | AuroraConfigRef                | reference to location of config files in Git                  |
| `applicationDeploymentRefs` | List<ApplicationDeploymentRef> | references to config files with environment and application   |
| `overrides`                 | List<AuroraConfigFile>         | list of overrides to configuration files from Git             |
| `deploy`                    | Boolean                        | flag indicating if generated configuration should be deployed |

`DeployFacade.executeDeploy` goes through the following steps:
1. checkoutAuroraConfig - fetch configuration files from Git and create context commands with specified overrides applied.
2. validateAuroraConfig - loops over context commands and create validated deployment contexts [AuroraDeploymentContext](./src/main/kotlin/no/skatteetaten/aurora/boober/model/AuroraDeploymentContext.kt)`.validate` will execute the feature`s validation logic.
Deployment will stop here if validation for any feature fails.
3. createNamespaces - creates namespace if it does not exist, if it exists then all resources in the namespace is fetched.
4. generateModifyResources - generates resources and create a `AuroraDeployCommand` for the resources.
Two strategies are used to generate the resources sequential generation utilizing the `generateSequentially` function and parallel generation utilizing the `generate` function.
5. deployToOpenShift - executes the deployment commands created in the previous step
6. sendNotification - sends notifications to notification channel specified in Boober`s configuration
7. storeResultToGit - updates AuroraConfig Git with deployment details

## Testing
Tests should be created or updated when developing new features or modifying existing features.
There are two kinds of testing that are relevant when working with features:
- unit tests for the feature located in `src/test/kotlin/no/skatteetaten/aurora/boober/feature`
- snapshot verification for deployment located in `src/test/kotlin/no/skatteetaten/aurora/boober/facade/DeployFacadeTest.kt`

### Unit tests
To make it easier to implement unit tests the abstract test classes `AbstractFeatureTest` and `AbstractMultiFeatureTest`
classes are provided in [AbstractFeatureTest](./src/test/kotlin/no/skatteetaten/aurora/boober/utils/AbstractFeatureTest.kt).
`AbstractFeatureTest` may be used when writing tests for a feature that does not rely on information from a different feature.
If the feature that is tested rely on information from another feature, then `AbstractMultiFeatureTest` should be used.

The abstract classes provide helper functions to generate deployment contexts and resources among other things.
The helper functions that are most used are `createAuroraDeploymentContext` and `generateResources`.

`createAuroraDeploymentContext` will execute the validation logic for the tested feature, and can be used to test the validation for various cases.
A collection of assertions, to assist in testing validation logic, are defined in the utils file `src/test/kotlin/no/skatteetaten/aurora/boober/utils/asserts.kt`.

`generateResources` will execute the `generate` and `generateSequentially` logic for the tested feature.
Depending on the generation logic it may be necessary to mock function calls.

### Snapshot test
The parameterized test `deploy application` in [DeployFacadeTest](src/test/kotlin/no/skatteetaten/aurora/boober/facade/DeployFacadeTest.kt)
is used to generate deployment specs and comparing them with snapshots. When adding or modifying features it may be necessary to update the
Aurora configuration files and snapshots.

The `deploy application` test runs the Aurora configuration through the controller layer down to the feature implementation.
This can be useful to debug and test behavior, without needing to run a local Boober instance against an actual environment.

As updating snapshot files can be a time-consuming job the class [ResourceLoader](src/test/kotlin/no/skatteetaten/aurora/boober/utils/ResourceLoader.kt)
provide the boolean property `shouldOverwriteResources` which will, when set to true, overwrite the snapshot files.

Aurora configuration files are located in [`src/test/resources/samples/config`](src/test/resources/samples/config)
Snapshot files are located in [`src/test/resources/samples/result/utv`](src/test/resources/samples/result/utv)