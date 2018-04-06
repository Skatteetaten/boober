package auroradeploymentspec

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        consumer(~/\/v1\/auroradeployspec\/[a-z]+\/[a-z]+\/[a-z]+\/formatted/),
        producer('/v1/auroradeployspec/auroraconfigname/utv/application/formatted')
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/deploymentspec-formatted.json'))
  }
}