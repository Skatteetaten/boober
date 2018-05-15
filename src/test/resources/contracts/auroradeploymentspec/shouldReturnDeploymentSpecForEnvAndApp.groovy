package auroradeploymentspec

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        stub(~/\/v1\/auroradeployspec\/[a-z]+\/[a-z]+\/[a-z]+/),
        test('/v1/auroradeployspec/auroraconfigname/utv/application')
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/deploymentspec.json'))
  }
}