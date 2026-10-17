import nodemailer from 'nodemailer';

/** Sends email (sign-in codes). Off until the server has an SMTP account to send through. */
export interface Mailer {
  readonly enabled: boolean;
  send(to: string, subject: string, text: string, html?: string): Promise<void>;
}

export interface MailConfig {
  host: string;
  port: number;
  secure: boolean;
  user: string;
  pass: string;
  from: string;
}

export function mailConfigFromEnv(env = process.env): MailConfig | null {
  if (!env.SMTP_HOST) return null;
  const port = Number(env.SMTP_PORT ?? 587);
  return {
    host: env.SMTP_HOST,
    port,
    // 465 is TLS from the start; 587 and 25 upgrade with STARTTLS.
    secure: env.SMTP_SECURE ? env.SMTP_SECURE === 'true' : port === 465,
    user: env.SMTP_USER ?? '',
    pass: env.SMTP_PASS ?? '',
    from: env.MAIL_FROM ?? 'WorthAbite <no-reply@worthabite.app>',
  };
}

export class SmtpMailer implements Mailer {
  private transport;

  constructor(private config: MailConfig | null) {
    this.transport = config
      ? nodemailer.createTransport({
          host: config.host,
          port: config.port,
          secure: config.secure,
          auth: config.user ? { user: config.user, pass: config.pass } : undefined,
          connectionTimeout: 10_000,
          greetingTimeout: 10_000,
          socketTimeout: 15_000,
        })
      : null;
  }

  get enabled() {
    return this.transport !== null;
  }

  async send(to: string, subject: string, text: string, html?: string) {
    if (!this.transport || !this.config) throw new Error('Email is not set up on this server.');
    await this.transport.sendMail({ from: this.config.from, to, subject, text, html });
  }
}

/** The email with a sign-in or verification code. */
export function codeEmail(code: string, purpose: 'login' | 'enable'): { subject: string; text: string; html: string } {
  const why = purpose === 'login'
    ? 'Someone (hopefully you) is signing in to your WorthAbite account.'
    : 'You asked to turn on two-step sign-in for your WorthAbite account.';
  const text = `${why}\n\nYour code is ${code}\n\nIt works for 10 minutes. If this wasn't you, change your password; nobody can sign in without this code.`;
  const html = `<div style="font-family:Arial,sans-serif;max-width:420px;margin:auto;padding:24px;background:#FBF6EF;border-radius:16px;color:#2B2B2B">
<p style="font-size:20px;font-weight:bold;margin:0 0 12px">Worth<span style="color:#F04E23">Abite</span></p>
<p>${why}</p>
<p style="font-size:32px;letter-spacing:8px;font-weight:bold;margin:20px 0">${code}</p>
<p style="color:#666">It works for 10 minutes. If this wasn't you, change your password; nobody can sign in without this code.</p>
</div>`;
  // The code goes in the subject too, so it shows in the phone's notification.
  return { subject: `${code} is your WorthAbite code`, text, html };
}
