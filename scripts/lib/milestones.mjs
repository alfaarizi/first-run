// Tasklet's funnel events. MILESTONES are the gated activation steps in order,
// shared so the labeler and the verifier accept the same catalog. The list
// view is a step the funnel tracks but never gates on.

export const MILESTONES = ['task_created', 'task_completed', 'completed_tasks_cleared']

export const TASK_LIST_VIEWED = 'task_list_viewed'
