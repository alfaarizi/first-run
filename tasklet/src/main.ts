import { track } from './firstrun'
import './style.css'

const form = document.querySelector<HTMLFormElement>('#task-form')!
const input = document.querySelector<HTMLInputElement>('#task-name')!
const list = document.querySelector<HTMLUListElement>('#tasks')!

form.addEventListener('submit', (submitEvent) => {
  submitEvent.preventDefault()
  const name = input.value.trim()
  if (!name) return
  const item = document.createElement('li')
  item.textContent = name
  list.append(item)
  input.value = ''
  void track('task_created', { task_count: list.children.length })
})
