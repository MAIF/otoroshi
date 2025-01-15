import { debounce } from 'lodash';
import { effect, signal } from 'signals-react-safe';
import { nextClient } from '../../services/BackOfficeServices';
import { mergeData } from './Compare/utils';

const DRAFT_VERSION_SIGNAL_TEMPLATE = {
  version: 'published',
  notFound: undefined,
  initialized: false,
  entityId: undefined,
};

const DRAFT_SIGNAL_TEMPLATE = {
  draft: undefined,
  rawDraft: undefined,
  // entityContent: undefined
};

export const draftVersionSignal = signal(DRAFT_VERSION_SIGNAL_TEMPLATE);

export const draftSignal = signal(DRAFT_SIGNAL_TEMPLATE);

export const entityContentSignal = signal(undefined);

export const updateEntityURLSignal = signal(undefined);

export const resetDraftSignal = (props = {}) => {
  // console.log('reset draft signal');

  draftSignal.value = {
    ...DRAFT_SIGNAL_TEMPLATE,
    processCallback: props.processCallback,
  };
  draftVersionSignal.value = DRAFT_VERSION_SIGNAL_TEMPLATE;
  entityContentSignal.value = undefined;
  updateEntityURLSignal.value = undefined;
};

const saveDraft = debounce(() => {
  if (draftSignal.value.initialized) {
    if (mergeData(draftSignal.value.rawDraft?.content, draftSignal.value.draft).changed) {
      // console.log('save draft', draftSignal.value);
      const { draft, processCallback } = draftSignal.value;

      let newValue = draft;

      if (processCallback) {
        newValue = processCallback(newValue);
      }

      nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS).update({
        ...draftSignal.value.rawDraft,
        content: newValue,
      });
    }
  } else draftSignal.value.initialized = true;
}, 1000);

effect(() => {
  saveDraft(draftSignal.value);
});
