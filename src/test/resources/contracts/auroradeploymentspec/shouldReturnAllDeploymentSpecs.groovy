package auroradeploymentspec

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        consumer(~/\/v1\/auroradeployspec\/[a-z]+\/(\?aid=.+)?/),
        producer('/v1/auroradeployspec/auroraconfigname/?aid=env/application')
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