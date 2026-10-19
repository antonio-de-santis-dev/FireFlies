package it.fireflies.audit;

/** Azioni registrate nell'audit. Il nome è il valore salvato in {@code audit_logs.action}. */
public enum AuditAction {
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    LOGIN_BLOCKED,
    ACCOUNT_LOCKED,
    LOGOUT,
    ACCESS_DENIED,
    PASSWORD_CHANGED,
    OWNER_BOOTSTRAPPED,
    USER_CREATED,
    USER_UPDATED,
    USER_PASSWORD_RESET
}
