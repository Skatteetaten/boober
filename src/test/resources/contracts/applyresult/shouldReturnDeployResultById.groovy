package applyresult

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        consumer(~/\/v1\/apply-result\/[a-z]+\/.+/),
        producer('/v1/apply-result/aos/123')
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/deployresult.json'))
  }
}
