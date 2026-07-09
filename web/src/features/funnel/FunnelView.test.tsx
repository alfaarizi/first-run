import { ApolloClient, HttpLink, InMemoryCache } from '@apollo/client'
import { ApolloProvider } from '@apollo/client/react'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, graphql } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, expect, test, vi } from 'vitest'

import { FunnelView } from '@/features/funnel/FunnelView'

const APP_ID = '019813f2-0000-7000-8000-000000000002'
const CREATED_TITLE = 'Create your first task'
const CONNECTED_TITLE = 'Connect a data source'
const DAY_MS = 24 * 60 * 60 * 1000

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

const requests: { from: string; to: string }[] = []

const server = setupServer(
  graphql.query('GetFunnel', ({ variables }) => {
    requests.push({ from: variables.from as string, to: variables.to as string })
    return HttpResponse.json({ data: funnelData })
  }),
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  requests.length = 0
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
  expect(daysAgo(requests[0].from)).toBeCloseTo(30, 1)
  expect(rangeDays(requests[0])).toBeCloseTo(30, 1)

  await user.click(sevenDays)

  await screen.findByRole('row', byTitle(CREATED_TITLE))
  expect(sevenDays).toHaveAttribute('aria-pressed', 'true')
  expect(thirtyDays).toHaveAttribute('aria-pressed', 'false')
  expect(daysAgo(requests.at(-1)!.from)).toBeCloseTo(7, 1)
  expect(rangeDays(requests.at(-1)!)).toBeCloseTo(7, 1)
})

test('slides the window forward on poll instead of widening it', async () => {
  vi.useFakeTimers()
  try {
    renderFunnel()
    await vi.waitFor(() => expect(requests).toHaveLength(1))
    const first = requests[0]

    // After one poll the window advances but holds its width, so a long-open
    // tab tracks now instead of stretching its start wider.
    await vi.advanceTimersByTimeAsync(15_000)
    await vi.waitFor(() => expect(requests.length).toBeGreaterThan(1))
    const next = requests.at(-1)!

    expect(new Date(next.to).getTime()).toBeGreaterThan(new Date(first.to).getTime())
    expect(rangeDays(next)).toBeCloseTo(rangeDays(first), 5)
  } finally {
    vi.useRealTimers()
  }
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
  return (Date.now() - new Date(iso).getTime()) / DAY_MS
}

function rangeDays(range: { from: string; to: string }): number {
  return (new Date(range.to).getTime() - new Date(range.from).getTime()) / DAY_MS
}
