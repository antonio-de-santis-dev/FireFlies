import { api } from './client';

/** Diagnostica WhatsApp (solo titolare): mai token, segreti o numeri, solo sì/no e conteggi. */
export type WhatsAppStatusInfo = {
  mode: 'DISABLED' | 'FAKE' | 'META_TEST' | string;
  realSendsPossible: boolean;
  webhookEnabled: boolean;
  configured: { accessToken: boolean; phoneNumberId: boolean; wabaId: boolean; webhookVerifyToken: boolean; appSecret: boolean };
  graphApiVersion: string;
  templateName: string;
  templateLanguage: string;
  testRecipients: number;
  counts: Record<'PENDING' | 'SENT' | 'DELIVERED' | 'READ' | 'FAILED' | 'UNCERTAIN' | 'SKIPPED', number>;
  lastError: { code: string; at: string } | null;
};

export const whatsAppStatus = () => api.get<WhatsAppStatusInfo>('/admin/notifications/whatsapp/status');
