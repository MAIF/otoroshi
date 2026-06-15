import React from 'react';

import { NgForm, NgSelectRenderer } from '../../../components/nginputs';
import { Row } from '../../../components/Row';
import { MAILERS_FORM } from '../../../pages/DataExportersPage';

export const SendMailFunction = {
  kind: 'core.send_mail',
  form_schema: {
    from: {
      type: 'string',
      label: 'From',
      props: { description: 'The sender email address' },
    },
    to: {
      type: 'array',
      label: 'To',
      props: { description: 'The recipient email addresses' },
    },
    subject: {
      type: 'string',
      label: 'Subject',
      props: { description: 'The email subject' },
    },
    html: {
      type: 'any',
      label: 'HTML',
      props: {
        language: 'html',
        editorOnly: true,
        height: 300,
        description: 'The email HTML content',
      },
    },
    mailer_config: {
      renderer: (props) => {
        const mailer_config = props.rootValue?.mailer_config;
        const kind = mailer_config?.kind;

        const flow =
          {
            mailjet: MAILERS_FORM.mailjetFormFlow,
            mailgun: MAILERS_FORM.mailgunFormFlow,
            sendgrid: MAILERS_FORM.sendgridFormFlow,
            scaleway: MAILERS_FORM.scalewayFormFlow,
            mailpace: MAILERS_FORM.mailpaceFormFlow,
            generic: MAILERS_FORM.genericFormFlow,
          }[kind] || [];

        return (
          <div className="pb-5">
            <NgForm
              schema={{
                kind: {
                  type: 'select',
                  label: 'Kind',
                  props: {
                    label: 'Kind',
                    options: [
                      { label: 'Mailjet', value: 'mailjet' },
                      { label: 'Mailgun', value: 'mailgun' },
                      { label: 'SendGrid', value: 'sendgrid' },
                      { label: 'Scaleway TEM', value: 'scaleway' },
                      { label: 'MailPace', value: 'mailpace' },
                      { label: 'Generic', value: 'generic' },
                    ],
                  },
                },
                ...{
                  mailjet: MAILERS_FORM.mailjetFormSchema,
                  mailgun: MAILERS_FORM.mailgunFormSchema,
                  sendgrid: MAILERS_FORM.sendgridFormSchema,
                  scaleway: MAILERS_FORM.scalewayFormSchema,
                  mailpace: MAILERS_FORM.mailpaceFormSchema,
                  generic: MAILERS_FORM.genericFormSchema,
                }[kind],
              }}
              flow={[
                {
                  type: 'group',
                  name: 'Configuration',
                  collapsable: false,
                  fields: ['kind', ...flow],
                },
              ]}
              value={mailer_config}
              onChange={(mailer_config) => {
                props.rootOnChange({
                  ...props.rootValue,
                  mailer_config,
                });
              }}
            />
          </div>
        );
      },
    },
  },
};
