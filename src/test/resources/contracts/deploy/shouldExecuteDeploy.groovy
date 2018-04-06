package deploy

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PUT'
    url $(
        consumer(~/\/v1\/apply\/[a-z]+/),
        producer('/v1/apply/paas')
    )
    headers {
      contentType(applicationJson())
    }
    body(
        applicationIds: [],
        overrides: {},
        deploy: true
    )
  }
  response {
    status 200
    body(file('responses/deploy.json'))
    headers {
      contentType(applicationJson())
    }
  }
}