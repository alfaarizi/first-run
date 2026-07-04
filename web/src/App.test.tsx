import { renderToString } from 'react-dom/server'
import { expect, test } from 'vitest'

import App from '@/App'

test('renders the dashboard shell', () => {
  expect(renderToString(<App />)).toContain('FirstRun')
})
