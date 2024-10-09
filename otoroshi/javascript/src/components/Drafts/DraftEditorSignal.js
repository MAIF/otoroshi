import { signal } from 'signals-react-safe';

const DRAFT_SIGNAL_TEMPLATE = {
  version: 'latest',
  content: undefined,
  rawDraft: undefined,
  entityId: undefined
}

export const draftSignal = signal(DRAFT_SIGNAL_TEMPLATE)

export const resetDraftSignal = () => {
  draftSignal.value = DRAFT_SIGNAL_TEMPLATE
}