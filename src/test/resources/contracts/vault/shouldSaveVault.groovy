package vault

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PUT'
    url $(
        consumer(~/\/v1\/vault\/[a-z]+/),
        producer('/v1/vault/vaultcollection')
    )
    headers {
      contentType(applicationJson())
    }
    body(
        name: 'responses',
        permissions: [],
        secrets: {}
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/vaults.json'))
  }
}