import { useQuery } from '@apollo/client/react'
import { useMemo, useState } from 'react'

import { graphql } from '@/gql'

const GET_FUNNEL = graphql(`
  query GetFunnel($appId: ID!, $from: DateTime) {
    app(id: $appId) {
      id
      name
      funnel(from: $from) {
        from
        to
        steps {
          milestone {
            id
            title
          }
          entered
          completed
          medianSecondsToComplete
        }
      }
    }
  }
`)

const RANGE_PRESETS = [7, 30, 90]
const DAY_MS = 86_400_000

/**
 * Renders users entered and completed per milestone over a selectable range,
 * polling so new events move the funnel without a reload.
 */
export function FunnelView({ appId }: { appId: string }) {
  const [days, setDays] = useState(30)
  const from = useMemo(() => new Date(Date.now() - days * DAY_MS).toISOString(), [days])
  const { data, previousData, error, loading } = useQuery(GET_FUNNEL, {
    variables: { appId, from },
    pollInterval: 15_000,
  })

  if (error) {
    return <p role="alert">The funnel could not load: {error.message}</p>
  }

  // The previous funnel stays up while a new range loads, so switching
  // presets never blanks the table.
  const app = (data ?? previousData)?.app
  if (!app) {
    return loading ? (
      <p role="status">Loading the funnel…</p>
    ) : (
      <p>No app matches the configured id.</p>
    )
  }

  const steps = app.funnel.steps
  const maxEntered = Math.max(1, ...steps.map((step) => step.entered))

  return (
    <section aria-labelledby="funnel-heading">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h2 id="funnel-heading" className="text-lg font-semibold">
          Activation funnel
        </h2>
        <div role="group" aria-label="Date range" className="flex gap-1">
          {RANGE_PRESETS.map((preset) => (
            <button
              key={preset}
              type="button"
              aria-pressed={days === preset}
              onClick={() => setDays(preset)}
              className="rounded border border-(--line) px-3 py-1.5 text-sm text-(--text-secondary) aria-pressed:border-(--series-1) aria-pressed:font-semibold aria-pressed:text-(--text-primary)"
            >
              Last {preset} days
            </button>
          ))}
        </div>
      </div>
      <table className="w-full border-collapse text-sm">
        <caption className="sr-only">
          Users per milestone of {app.name} over the last {days} days.
        </caption>
        <thead>
          <tr className="border-b border-(--line) text-left text-(--text-secondary)">
            <th scope="col" className="py-2 pr-4 font-medium">
              Step
            </th>
            <th scope="col" className="numeric py-2 pr-4 font-medium">
              Entered
            </th>
            <th scope="col" className="numeric py-2 pr-4 font-medium">
              Completed
            </th>
            <th scope="col" className="numeric py-2 pr-4 font-medium">
              Conversion
            </th>
            <th scope="col" className="numeric py-2 pr-4 font-medium">
              Median time
            </th>
            <th scope="col" className="w-2/5">
              <span className="sr-only">Completion bar</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {steps.map((step) => (
            <tr key={step.milestone.id} className="border-b border-(--line)">
              <th scope="row" className="py-2 pr-4 text-left font-normal">
                {step.milestone.title}
              </th>
              <td className="numeric py-2 pr-4">{step.entered}</td>
              <td className="numeric py-2 pr-4">{step.completed}</td>
              <td className="numeric py-2 pr-4">{conversion(step.completed, step.entered)}</td>
              <td className="numeric py-2 pr-4">
                {step.medianSecondsToComplete == null
                  ? '—'
                  : formatSeconds(step.medianSecondsToComplete)}
              </td>
              <td aria-hidden="true" className="min-w-32 py-2">
                <div
                  className="funnel-track"
                  style={{ width: `${(step.entered / maxEntered) * 100}%` }}
                >
                  <div
                    className="funnel-fill"
                    style={{
                      width: `${step.entered === 0 ? 0 : (step.completed / step.entered) * 100}%`,
                    }}
                  />
                </div>
              </td>
            </tr>
          ))}
          {steps.length === 0 && (
            <tr>
              <td colSpan={6} className="py-4 text-(--text-secondary)">
                No milestones defined yet.
              </td>
            </tr>
          )}
        </tbody>
      </table>
      <p className="mt-3 flex gap-4 text-xs text-(--text-secondary)">
        <span>
          <span aria-hidden="true" className="mr-1.5 inline-block size-2.5 rounded-xs bg-(--series-1) align-middle" />
          Completed
        </span>
        <span>
          <span aria-hidden="true" className="mr-1.5 inline-block size-2.5 rounded-xs bg-(--track) align-middle" />
          Entered, not yet completed
        </span>
      </p>
    </section>
  )
}

function conversion(completed: number, entered: number): string {
  return entered === 0 ? '—' : `${Math.round((completed / entered) * 100)}%`
}

function formatSeconds(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3_600) return `${Math.round(seconds / 60)}m`
  if (seconds < 86_400) return `${Math.round(seconds / 3_600)}h`
  return `${Math.round(seconds / 86_400)}d`
}
