import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

// A full URL because the dashboard is a separate compose service on 5173, not
// the Tasklet baseURL of 5174.
const DASHBOARD_URL = 'http://localhost:5173/'

const CREATED_STEP = /create your first task/i
const COMPLETED_STEP = /complete a task/i
const CLEARED_STEP = /clear completed tasks/i

// A fresh browser context is a new end user (the hash lives in localStorage), so
// this walk completes all three milestones. Parallel specs may move counts by
// more than one, so the test asserts only that each step moved.
test('a full task journey in Tasklet moves every funnel step', async ({ page }) => {
  test.setTimeout(120_000)
  const created = await completedCount(page, CREATED_STEP)
  const completed = await completedCount(page, COMPLETED_STEP)
  const cleared = await completedCount(page, CLEARED_STEP)

  await page.goto('/')
  await page.getByLabel('New task').fill('Watch the funnel move')
  const createdAccepted = accepted(page, 'task_created')
  await page.getByRole('button', { name: 'Add task' }).click()
  expect((await createdAccepted).status()).toBe(202)

  const completedAccepted = accepted(page, 'task_completed')
  await page.getByRole('checkbox', { name: 'Watch the funnel move' }).check()
  expect((await completedAccepted).status()).toBe(202)

  const clearedAccepted = accepted(page, 'completed_tasks_cleared')
  await page.getByRole('button', { name: 'Clear completed' }).click()
  expect((await clearedAccepted).status()).toBe(202)

  // The events flow through the gateway, the stream, and the projection
  // before the dashboard can read them, so poll rather than assert once.
  await expect
    .poll(() => completedCount(page, CREATED_STEP), { timeout: 60_000 })
    .toBeGreaterThan(created)
  await expect
    .poll(() => completedCount(page, COMPLETED_STEP), { timeout: 60_000 })
    .toBeGreaterThan(completed)
  await expect
    .poll(() => completedCount(page, CLEARED_STEP), { timeout: 60_000 })
    .toBeGreaterThan(cleared)
})

/**
 * Matches the batch carrying the named event, not whichever ingest call lands
 * first. The page also tracks a view event on load.
 */
function accepted(page: Page, event: string) {
  return page.waitForResponse(
    (response) =>
      response.url().includes('/v1/e') &&
      (response.request().postData() ?? '').includes(`"${event}"`),
  )
}

async function completedCount(page: Page, step: RegExp): Promise<number> {
  await page.goto(DASHBOARD_URL)
  const row = page.getByRole('row', { name: step })
  await row.waitFor()
  // Cell order: entered, completed, conversion, median time, bar.
  return Number(await row.getByRole('cell').nth(1).textContent())
}
