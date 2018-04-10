package deploy

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'DELETE'
    url $(
        consumer(~/\/v1\/vault\/[a-z]+\/.*/),
        producer('/v1/vault/vaultcollection/vault/filename')
    )
    headers {
      contentType(applicationJson())
    }
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/empty-vault.json'))
  }
}