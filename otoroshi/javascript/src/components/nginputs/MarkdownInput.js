import React, { useState, useEffect, useRef } from 'react';
import showdown from 'showdown';
import classNames from 'classnames';

import '@fortawesome/fontawesome-free/css/all.css';
import 'highlight.js/styles/monokai.css';
import hljs from 'highlight.js';

const CodeInput = React.lazy(() => Promise.resolve(require('../inputs/CodeInput')));

const converter = new showdown.Converter({
  omitExtraWLInCodeBlocks: true,
  ghCompatibleHeaderId: true,
  parseImgDimensions: true,
  simplifiedAutoLink: true,
  tables: true,
  tasklists: true,
  requireSpaceBeforeHeadingText: true,
  ghMentions: true,
  emoji: true,
  ghMentionsLink: '/{u}',
});

export const MarkdownInput = (props) => {
  const [preview, setPreview] = useState(props.preview);
  const ref = useRef();

  useEffect(() => {
    if (preview) {
      showPreview();
    }
  }, [preview, props.value]);

  const commands = [
    {
      name: 'Add header',
      icon: 'heading',
      inject: (range) => (!range ? '#' : [{ from: range.from, insert: '# ' }]),
    },
    {
      name: 'Add bold text',
      icon: 'bold',
      inject: (range) =>
        !range
          ? '**  **'
          : [
              { from: range.from, insert: '**' },
              { from: range.to, insert: '**' },
            ],
    },
    {
      name: 'Add italic text',
      icon: 'italic',
      inject: (range) =>
        !range
          ? '* *'
          : [
              { from: range.from, insert: '*' },
              { from: range.to, insert: '*' },
            ],
    },
    {
      name: 'Add strikethrough text',
      icon: 'strikethrough',
      inject: (range) =>
        !range
          ? '~~ ~~'
          : [
              { from: range.from, insert: '~~' },
              { from: range.to, insert: '~~' },
            ],
    },
    {
      name: 'Add link',
      icon: 'link',
      inject: (range) =>
        !range
          ? '[ ](url)'
          : [
              { from: range.from, insert: '[' },
              { from: range.to, insert: '](url)' },
            ],
    },
    {
      name: 'Add code',
      icon: 'code',
      inject: (range) =>
        !range
          ? '```\n\n```\n'
          : [
              { from: range.from, insert: '```\n' },
              { from: range.to, insert: '\n```\n' },
            ],
    },
    {
      name: 'Add quotes',
      icon: 'quote-right',
      inject: (range) => (!range ? '> ' : [{ from: range.from, insert: '> ' }]),
    },
    {
      name: 'Add image',
      icon: 'image',
      inject: (range) =>
        !range
          ? '![ ](image-url)'
          : [
              { from: range.from, insert: '![' },
              { from: range.to, insert: '](image-url)' },
            ],
    },
    {
      name: 'Add unordered list',
      icon: 'list-ul',
      inject: (range) => (!range ? '* ' : [{ from: range.from, insert: '* ' }]),
    },
    {
      name: 'Add ordered list',
      icon: 'list-ol',
      inject: (range) => (!range ? '1. ' : [{ from: range.from, insert: '1. ' }]),
    },
    {
      name: 'Add check list',
      icon: 'tasks',
      inject: (range) => (!range ? '* [ ] ' : [{ from: range.from, insert: '* [ ] ' }]),
    },
  ];

  const showPreview = () => {
    const parent = [...document.getElementsByClassName('preview')];
    if (parent.length > 0)
      [...parent[0].querySelectorAll('pre code')].forEach((block) => hljs.highlightBlock(block));
  };

  const injectButtons = () => {
    const classes = props.classes;
    return commands.map((command, idx) => {
      if (command.component) {
        return command.component(idx);
      }
      return (
        <button
          type="button"
          className={classNames(classes.btn_for_descriptionToolbar)}
          aria-label={command.name}
          title={command.name}
          key={`toolbar-btn-${idx}`}
          onClick={() => {
            const editor = ref.current;
            const selections = editor.state.selection.ranges;
            if (selections.length === 1 && selections[0].from === selections[0].to)
              editor.dispatch({
                changes: {
                  from: 0,
                  to: editor.state.doc.length,
                  insert: editor.state.doc.toString() + command.inject(),
                },
              });
            else {
              editor.dispatch(
                editor.state.changeByRange((range) => ({
                  changes: command.inject(range),
                  range,
                }))
              );
            }
          }}>
          <i className={`fas fa-${command.icon}`} />
        </button>
      );
    });
  };

  const classes = props.classes;

  return (
    <div className={classNames(props.className)}>
      {!props.readOnly && (
        <div
          style={{
            marginBottom: 10,
          }}>
          <div>
            <div>
              <button
                type="button"
                className={classNames(classes.btn, classes.btn_sm)}
                style={{
                  color: !preview ? '#7f96af' : 'white',
                  backgroundColor: preview ? '#7f96af' : 'white',
                }}
                onClick={() => setPreview(false)}>
                Write
              </button>
              <button
                type="button"
                className={classNames(classes.btn, classes.btn_sm, classes.ml_5)}
                style={{
                  color: preview ? '#7f96af' : 'white',
                  backgroundColor: preview ? 'white' : '#7f96af',
                }}
                onClick={() => setPreview(true)}>
                Preview
              </button>
            </div>
          </div>
          <div className={classNames(classes.flex, classes.flexWrap)}>{injectButtons()}</div>
        </div>
      )}
      {!preview && <CodeInput {...props} setRef={(e) => (ref.current = e)} />}
      {preview && (
        <div
          className="preview"
          dangerouslySetInnerHTML={{ __html: converter.makeHtml(props.value) }}
        />
      )}
    </div>
  );
};
