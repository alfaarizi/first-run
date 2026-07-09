import { ApolloClient, HttpLink, InMemoryCache } from '@apollo/client'
import { ApolloProvider } from '@apollo/client/react'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, graphql } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, expect, test } from 'vitest'

import { FunnelView } from '@/features/funnel/FunnelView'

const APP_ID = '019813f2-0000-7000-8000-000000000002'
const CREATED_TITLE = 'Create your first task'
const CONNECTED_TITLE = 'Connect a data source'

// A row's accessible name includes its numeric cells, match title as substring.
const byTitle = (title: string) => ({ name: new RegExp(title, 'i') })

const funnelData = {
  app: {
    __typename: 'App',
    id: APP_ID,
    name: 'Tasklet',
    funnel: {
      __typename: 'Funnel',
      from: '2026-06-08T00:00:00Z',
      to: '2026-07-08T00:00:00Z',
      steps: [
        {
          __typename: 'FunnelStep',
          milestone: {
            __typename: 'Milestone',
            id: '019813f2-0000-7000-8000-000000000003',
            title: CREATED_TITLE,
          },
          entered: 8,
          completed: 4,
          medianSecondsToComplete: 7200,
        },
        {
          __typename: 'FunnelStep',
          milestone: {
            __typename: 'Milestone',
            id: '019813f2-0000-7000-8000-000000000004',
            title: CONNECTED_TITLE,
          },
          entered: 4,
          completed: 0,
          medianSecondsToComplete: null,
        },
      ],
    },
  },
}

const requestedFroms: string[] = []

const server = setupServer(
  graphql.query('GetFunnel', ({ variables }) => {
    requestedFroms.push(variables.from as string)
    return HttpResponse.json({ data: funnelData })
  }),
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  requestedFroms.length = 0
  cleanup()
})
afterAll(() => server.close())

function renderFunnel() {
  const client = new ApolloClient({
    link: new HttpLink({ uri: 'http://localhost/graphql' }),
    cache: new InMemoryCache(),
  })
  return render(
    <ApolloProvider client={client}>
      <FunnelView appId={APP_ID} />
    </ApolloProvider>,
  )
}

test('renders one table row per funnel step with its counts', async () => {
  renderFunnel()

  const firstStep = await screen.findByRole('row', byTitle(CREATED_TITLE))
  expect(firstStep).toHaveTextContent('8')
  expect(firstStep).toHaveTextContent('4')
  expect(firstStep).toHaveTextContent('50%')
  expect(firstStep).toHaveTextContent('2h')

  const secondStep = screen.getByRole('row', byTitle(CONNECTED_TITLE))
  expect(secondStep).toHaveTextContent('0%')
  expect(secondStep).toHaveTextContent('—')
})

test('defaults to the last 30 days and refetches when a preset is pressed', async () => {
  const user = userEvent.setup()
  renderFunnel()
  await screen.findByRole('row', byTitle(CREATED_TITLE))

  const thirtyDays = screen.getByRole('button', { name: 'Last 30 days' })
  const sevenDays = screen.getByRole('button', { name: 'Last 7 days' })
  expect(thirtyDays).toHaveAttribute('aria-pressed', 'true')
  expect(sevenDays).toHaveAttribute('aria-pressed', 'false')
  expect(daysAgo(requestedFroms[0])).toBeCloseTo(30, 1)

  await user.click(sevenDays)

  await screen.findByRole('row', byTitle(CREATED_TITLE))
  expect(sevenDays).toHaveAttribute('aria-pressed', 'true')
  expect(thirtyDays).toHaveAttribute('aria-pressed', 'false')
  expect(daysAgo(requestedFroms[requestedFroms.length - 1])).toBeCloseTo(7, 1)
})

test('reports a failed load without rendering a table', async () => {
  server.use(
    graphql.query('GetFunnel', () =>
      HttpResponse.json({ errors: [{ message: 'The request carries no tenant.' }] }),
    ),
  )
  renderFunnel()

  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent('The funnel could not load')
  expect(screen.queryByRole('table')).toBeNull()
})

function daysAgo(iso: string): number {
  return (Date.now() - new Date(iso).getTime()) / 86_400_000
}
