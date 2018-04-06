package deploy

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PUT'
    url $(
        consumer(~/\/v1\/vault\/[a-z]+\/.*/),
        producer('/v1/vault/vaultcollection/vault/filename')
    )
    headers {
      contentType(applicationJson())
    }
    body(
        contents: 'dGVzdA=='
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/vaultfile.json'))
  }
}