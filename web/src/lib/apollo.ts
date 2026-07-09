import { ApolloClient, HttpLink, InMemoryCache } from '@apollo/client'

/**
 * The demo tenant scripts/seed.sql inserts. The server reads the tenant
 * header only when the local trusted-header switch is on (compose.yaml).
 */
export const demoTenantId = '019813f2-0000-7000-8000-000000000001'

/** The demo app scripts/seed.sql inserts under the demo tenant. */
export const demoAppId = '019813f2-0000-7000-8000-000000000002'

/** Creates the dashboard client, which authenticates as the demo tenant. */
export function createApolloClient(): ApolloClient {
  return new ApolloClient({
    link: new HttpLink({
      uri: '/graphql',
      headers: { 'X-FirstRun-Tenant': demoTenantId },
    }),
    cache: new InMemoryCache(),
  })
}
