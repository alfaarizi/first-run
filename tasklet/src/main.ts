import './style.css'

// Real integrations hash their user ID and call identify with the result. A
// per-browser random value is close enough here, and the widget owns sessions.
const endUserHash = stored(localStorage, 'firstrun.end_user_hash', () =>
  crypto.randomUUID().replaceAll('-', ''),
)
window.fr?.identify(endUserHash)

const form = document.querySelector<HTMLFormElement>('#task-form')!
const input = document.querySelector<HTMLInputElement>('#task-name')!
const list = document.querySelector<HTMLUListElement>('#tasks')!
const clear = document.querySelector<HTMLButtonElement>('#clear-completed')!

window.fr?.track('task_list_viewed')

form.addEventListener('submit', (submitEvent) => {
  submitEvent.preventDefault()
  const name = input.value.trim()
  if (!name) return
  const item = document.createElement('li')
  const done = document.createElement('input')
  done.type = 'checkbox'
  done.addEventListener('change', () => {
    if (done.checked) {
      window.fr?.track('task_completed', { task_count: list.children.length })
    }
  })
  const label = document.createElement('label')
  label.append(done, name)
  item.append(label)
  list.append(item)
  input.value = ''
  window.fr?.track('task_created', { task_count: list.children.length })
})

clear.addEventListener('click', () => {
  const done = list.querySelectorAll<HTMLInputElement>('input[type=checkbox]:checked')
  if (done.length === 0) return
  done.forEach((box) => box.closest('li')!.remove())
  window.fr?.track('completed_tasks_cleared', { task_count: list.children.length })
})

function stored(storage: Storage, key: string, make: () => string): string {
  const existing = storage.getItem(key)
  if (existing) return existing
  const value = make()
  storage.setItem(key, value)
  return value
}
