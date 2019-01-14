package vault

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        stub(~/\/v1\/vault\/[a-z]+\/.*/),
        test('/v1/vault/vaultcollection/vault/filename')
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
  }
}