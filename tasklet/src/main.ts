import { track } from './firstrun'
import './style.css'

const form = document.querySelector<HTMLFormElement>('#task-form')!
const input = document.querySelector<HTMLInputElement>('#task-name')!
const list = document.querySelector<HTMLUListElement>('#tasks')!
const clear = document.querySelector<HTMLButtonElement>('#clear-completed')!

void track('task_list_viewed')

form.addEventListener('submit', (submitEvent) => {
  submitEvent.preventDefault()
  const name = input.value.trim()
  if (!name) return
  const item = document.createElement('li')
  const done = document.createElement('input')
  done.type = 'checkbox'
  done.addEventListener('change', () => {
    if (done.checked) {
      void track('task_completed', { task_count: list.children.length })
    }
  })
  const label = document.createElement('label')
  label.append(done, name)
  item.append(label)
  list.append(item)
  input.value = ''
  void track('task_created', { task_count: list.children.length })
})

clear.addEventListener('click', () => {
  const done = list.querySelectorAll<HTMLInputElement>('input[type=checkbox]:checked')
  if (done.length === 0) return
  done.forEach((box) => box.closest('li')!.remove())
  void track('completed_tasks_cleared', { task_count: list.children.length })
})
