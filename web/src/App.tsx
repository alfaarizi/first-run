import { ApolloProvider } from '@apollo/client/react'

import { FunnelView } from '@/features/funnel/FunnelView'
import { createApolloClient, demoAppId } from '@/lib/apollo'

const client = createApolloClient()

function App() {
  return (
    <ApolloProvider client={client}>
      <main className="mx-auto max-w-3xl p-6">
        <h1 className="mb-6 text-2xl font-bold">FirstRun</h1>
        <FunnelView appId={demoAppId} />
      </main>
    </ApolloProvider>
  )
}

export default App
