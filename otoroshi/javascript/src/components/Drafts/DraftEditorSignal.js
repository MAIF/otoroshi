import { signal } from 'signals-react-safe';

const DRAFT_VERSION_SIGNAL_TEMPLATE = {
  version: 'published',
  notFound: undefined,
}

const DRAFT_SIGNAL_TEMPLATE = {
  draft: undefined,
  rawDraft: undefined,
  entityContent: undefined
}

export const draftVersionSignal = signal(DRAFT_VERSION_SIGNAL_TEMPLATE)

export const draftSignal = signal(DRAFT_SIGNAL_TEMPLATE)

export const resetDraftSignal = () => {
  draftSignal.value = DRAFT_SIGNAL_TEMPLATE
  draftVersionSignal.value = DRAFT_VERSION_SIGNAL_TEMPLATE
}