import React from 'react';
import Thumbtack from './Thumbtack';
import { signal, useSignalValue } from 'signals-react-safe';
import { draftVersionSignal } from './Drafts/DraftEditorSignal';
import { PublisDraftButton } from './Drafts/DraftEditor';
import { useLocation } from 'react-router-dom';

export const dynamicTitleContent = signal();

const EXCLUDED_DRAFT_PAGES = ['/routes'];

export function DynamicTitleSignal(props) {
  const content = useSignalValue(dynamicTitleContent);
  const draftVersion = useSignalValue(draftVersionSignal);

  const { pathname } = useLocation();

  if (!content) {
    return null;
  }

  if (React.isValidElement(content)) {
    return (
      <div style={{ position: 'relative' }}>
        {content}

        {draftVersion.version === 'draft' &&
          !EXCLUDED_DRAFT_PAGES.find((path) => pathname.includes(path)) && <PublisDraftButton />}
      </div>
    );
  }

  if (typeof content === 'object' && !Array.isArray(content) && content !== null) {
    return <div style={{ position: 'relative' }}>
      <div className="page-header">
        <h3 className="page-header_title">
          {content.value}
          {!content.noThumbtack && <Thumbtack {...props} getTitle={() => content.value} />}
          {content.children}
        </h3>
      </div>
    </div>
  }

  return (
    <div style={{ position: 'relative' }}>
      <div className="page-header">
        <h3 className="page-header_title">
          {content}
          <Thumbtack {...props} getTitle={() => content} />
          {draftVersion.version === 'draft' &&
            !EXCLUDED_DRAFT_PAGES.find((path) => pathname.includes(path)) && <PublisDraftButton />}
        </h3>
      </div>
    </div>
  );
}
