
## Aurora features
Handlers for Aurora features are defined in `no.skatteetaten.aurora.boober.feature`.

### Features
Features must extend the interface [Feature](./src/main/kotlin/no/skatteetaten/aurora/boober/feature/Feature.kt).
The interface include documentation for the functions that needs to be implemented.

A feature-file takes a part of the AuroraConfig and transforms it into a [`AuroraResource`](./src/main/kotlin/no/skatteetaten/aurora/boober/model/AuroraResource.kt).
The AuroraResource file represents the Kubernetes resource that will be applied to OpenShift.

The implemented functions are used in [AuroraDeploymentContext](./src/main/kotlin/no/skatteetaten/aurora/boober/model/AuroraDeploymentContext.kt)
used by [AuroraDeploymentContextService](./src/main/kotlin/no/skatteetaten/aurora/boober/service/AuroraDeploymentContextService.kt)
which in turn is used by [DeployFacade](./src/main/kotlin/no/skatteetaten/aurora/boober/facade/DeployFacade.kt). 

The functions that can be implemented, in running order, are:

| #   | function                          | return value                                                                         | desc                                                                                                                                              |
|-----|-----------------------------------|--------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| 1   | `enable`                          | `bool`                                                                               | indicates if feature should run                                                                                                                   |
| 2   | `handlers`                        | `Set<AuroraConfigFieldHandler>`                                                      | create handlers for configuration fields                                                                                                          |
| 3   | `createContext`                   | [FeatureContext](./src/main/kotlin/no/skatteetaten/aurora/boober/feature/Feature.kt) | create context for a given feature, context is sent to `validate`, `generate`, `modify` steps (should only be used for time-consuming operations) |
| 4   | `validate`                        | `List<Exception>`                                                                    | performs validation for a given feature                                                                                                           |
| 5   | `generate`/`generateSequentially` | `Set<AuroraResource>`                                                                | generates AuroraResources for a given feature                                                                                                     |
| 6   | `modify`                          | `void`                                                                               | modify generated resources, performed after all features has completed the generate step                                                          |

#### Handlers
The handlers function returns a set of [AuroraConfigFieldHandler](./src/main/kotlin/no/skatteetaten/aurora/boober/model/AuroraConfigFieldHandler.kt)
which provides handling of a given AuroraConfig field pointer with default value and validation.
- Pre-defined validation logic can be found in [jsonNodeUtils.kt](./src/main/kotlin/no/skatteetaten/aurora/boober/utils/jsonNodeUtils.kt).
- Validation severity can be set with the validationSeverity parameter (default value is [ErrorType.ILLEGAL](./src/main/kotlin/no/skatteetaten/aurora/boober/model/errors.kt)).
- `ErrorType.WARNING` can be used when the validation should warn about configuration but allow deploying.
- `allowedFileTypes` can be used to limit where configuration keys can be placed, by passing a set of [`AuroraConfigFileType`](./src/main/kotlin/no/skatteetaten/aurora/boober/model/AuroraConfigFile.kt).
For example, if a property should only be available in a base-file or app-file then a set containing `AuroraConfigFileType.BASE` and `AuroraConfigFileType.App` should be passed.
  - By default `allowedFileTypes` is null, and will permit placing the configuration property in any file.
  - If `allowedFileTypes` is empty then the configuration property will be invalid in any file.

#### Generation logic
The interface provides two functions for generating resources: `generate` and `generateSequentially`.
A feature should normally only implement the `generate` function which will be executed in parallel, if `generateSequentially` is implemented then `generate` should be omitted.
The `generateSequentially` function is intended for resources where parallel generation may result in duplicate resources (for example shared databases [DatabaseFeature](./src/main/kotlin/no/skatteetaten/aurora/boober/feature/DatabaseFeature.kt)).


The behavior of the generation logic may vary based on requirements.
- some features may create the resource and AuroraResource
- some features may create the AuroraResource and let other tooling create the resource
- some features may create some of the necessary resources (such as secrets) and AuroraResource, and let other tooling create remaining resources.

#### AuroraDeploymentSpec
[AuroraDeploymentSpec](./src/main/kotlin/no/skatteetaten/aurora/boober/model/AuroraDeploymentSpec.kt) is a class passed to the functions `handlers`, `validate`, `generate` and `generateSequentially`.
The class contains the fields for a given deployment and helper functions for extracting fields for validation and generation purposes.
It is recommended to look at how existing features makes use of the class.

- `handlers` will receive a single header spec
- the remaining functions will receive a header spec plus a spec for each handler defined

#### Utilities
Utility functionality is located in [no.skatteetaten.aurora.boober.utils](./src/main/kotlin/no/skatteetaten/aurora/boober/utils)
and it can be beneficial to check if the existing utility functions provides the desired functionality.
These files may be relevant when working with features:
- [jsonNodeUtils.kt](./src/main/kotlin/no/skatteetaten/aurora/boober/utils/jsonNodeUtils.kt)
- [StringUtils.kt](./src/main/kotlin/no/skatteetaten/aurora/boober/utils/StringUtils.kt)
- [UrlParser.kt](./src/main/kotlin/no/skatteetaten/aurora/boober/utils/UrlParser.kt)

Other features may also have the desired functionality defined locally. In such cases it is encouraged to
move the functionality to `no.skatteetaten.aurora.boober.utils` for reuse.

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
3. createNamespaces - creates namespace if it does not exist. If it exists, then all resources in the namespace are fetched.
4. generateModifyResources - generates resources and create a `AuroraDeployCommand` for the resources.
Two strategies are used to generate the resources sequential generation utilizing the `generateSequentially` function and parallel generation utilizing the `generate` function.
5. deployToOpenShift - executes the deployment commands created in the previous step
6. sendNotification - sends notifications to notification channel specified in Boober`s configuration
7. storeResultToGit - updates AuroraConfig Git with deployment details

## Testing
Tests should be created or updated when developing new features or modifying existing features.
There are two kinds of testing that are relevant when working with features:
- unit tests for the feature located in [`src/test/kotlin/no/skatteetaten/aurora/boober/feature`](./src/test/kotlin/no/skatteetaten/aurora/boober/feature)
- snapshot verification for deployment located in [`src/test/kotlin/no/skatteetaten/aurora/boober/facade/DeployFacadeTest.kt`](./src/test/kotlin/no/skatteetaten/aurora/boober/feature)

Snapshot verification use sample AuroraConfig located in [`src/test/resources/samples/config`](./src/test/resources/samples/config).
Configuration for new/modified features can be added to the sample configuration.

### Unit tests
To make it easier to implement unit tests the abstract test classes `AbstractFeatureTest` and `AbstractMultiFeatureTest`
classes are provided in [AbstractFeatureTest](./src/test/kotlin/no/skatteetaten/aurora/boober/utils/AbstractFeatureTest.kt).
`AbstractFeatureTest` may be used when writing tests for a feature that does not rely on information from a different feature.
If the feature that is tested rely on information from another feature, then `AbstractMultiFeatureTest` should be used.

The abstract classes provide helper functions to generate deployment contexts and resources among other things.
The helper functions that are most used are `createAuroraDeploymentContext` and `generateResources`.

`createAuroraDeploymentContext` will execute the validation logic for the tested feature, and can be used to test the validation for various cases.
A collection of assertions, to assist in testing validation logic, are defined in the utils file [`src/test/kotlin/no/skatteetaten/aurora/boober/utils/asserts.kt`](./src/test/kotlin/no/skatteetaten/aurora/boober/utils/asserts.kt).

`generateResources` will execute the `generate` and `generateSequentially` logic for the tested feature.
Depending on the generation logic it may be necessary to mock function calls.

### Snapshot test
The parameterized test `deploy application` in [DeployFacadeTest](./src/test/kotlin/no/skatteetaten/aurora/boober/facade/DeployFacadeTest.kt)
is used to generate deployment specs and comparing them with snapshots. When adding or modifying features it may be necessary to update the
Aurora configuration files and snapshots.

The `deploy application` test runs the Aurora configuration through the controller layer down to the feature implementation.
This can be useful to debug and test behavior, without needing to run a local Boober instance against an actual environment.

As updating snapshot files can be a time-consuming job the class [ResourceLoader](./src/test/kotlin/no/skatteetaten/aurora/boober/utils/ResourceLoader.kt)
provide the boolean property `shouldOverwriteResources` which will, when set to true, overwrite the snapshot files.

- Aurora configuration files are located in [`src/test/resources/samples/config`](./src/test/resources/samples/config)
- Snapshot files are located in [`src/test/resources/samples/result/utv`](./src/test/resources/samples/result/utv)

### Spring cloud contract

[Spring Cloud Contract](https://cloud.spring.io/spring-cloud-contract/) helps implement [Consumer Driven Contracts](https://martinfowler.com/articles/consumerDrivenContracts.html).

Boober is setup to generate Spock tests from the contracts. There is a convention to follow when adding new tests.

- Create a package in `src/test/resources/contracts` that contains the name of the controller. If the controller is called `ClientConfigControllerV1` the package should be named `clientconfig`.
- Create a contract inside this folder. If you need a json-response body, this can be added as a separate json-file inside the responses folder. In the clientconfig example these files are put into `src/test/resources/contract/clientconfig/responses`.
- In the folder `src/test/kotlin` and the package `no.skatteetaten.aurora.boober.contracts` create a kotlin class called [name]Base.
  In the example it will be `ClientconfigBase`. This is picked up by [spring cloud contract automatically](https://docs.spring.io/spring-cloud-contract/docs/3.1.x/reference/html/maven-project.html#maven-different-base) and added as a base class to the generated test.
- In the Base-class add the required setup code, such as creating mock services. It should also extend `AbstractContractBase`, which provides a few helpful helper methods available to get the content of the response-json files and setting up the mock controller.
- Run `./gradlew generateContractTests` to generate the Spock tests from the contracts. The tests will be automatically generated and run with the normal `./gradlew build` command.
- When building the project a `stubs.jar` file is generated, for Boober this will be called something like: `boober-SNAPSHOT-stubs.jar`. This can be uploaded to the repository along with other artifacts and then used by the clients that communicates with the Boober API when testing.
