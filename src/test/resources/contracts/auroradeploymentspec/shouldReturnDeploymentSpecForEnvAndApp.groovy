package auroradeploymentspec

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url '/v1/auroradeployspec/aurora-config-name/utv/application'
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/deploymentspec.json'))
  }
}