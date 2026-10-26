export class AdminApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string) {
    super(code);
    this.name = "AdminApiError";
    this.status = status;
    this.code = code;
  }
}

interface AdminApiOptions {
  url: string;
  publishableKey: string;
  getAccessToken: () => Promise<string | null>;
  onUnauthorized?: () => void;
  timeoutMs?: number;
}

interface ApiEnvelope {
  ok?: unknown;
  data?: unknown;
  error?: unknown;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function errorCode(body: unknown): string {
  if (isRecord(body) && typeof body.error === "string" && body.error.length > 0) {
    return body.error;
  }
  return "request_failed";
}

export class AdminApi {
  private readonly url: string;
  private readonly publishableKey: string;
  private readonly getAccessToken: () => Promise<string | null>;
  private readonly onUnauthorized?: () => void;
  private readonly timeoutMs: number;

  constructor(options: AdminApiOptions) {
    this.url = options.url;
    this.publishableKey = options.publishableKey;
    this.getAccessToken = options.getAccessToken;
    this.onUnauthorized = options.onUnauthorized;
    this.timeoutMs = options.timeoutMs ?? 20_000;
  }

  async request<T>(action: string, payload: Record<string, unknown> = {}): Promise<T> {
    if (this.url === "" || this.publishableKey === "") {
      throw new AdminApiError(0, "not_configured");
    }
    const token = await this.getAccessToken();
    if (!token) {
      this.onUnauthorized?.();
      throw new AdminApiError(401, "unauthorized");
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    let response: Response;
    try {
      response = await fetch(this.url, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          apikey: this.publishableKey,
          authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ action, ...payload }),
        signal: controller.signal,
        credentials: "omit",
        cache: "no-store",
      });
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        throw new AdminApiError(0, "timeout");
      }
      throw new AdminApiError(0, "network_error");
    } finally {
      clearTimeout(timer);
    }

    const text = await response.text();
    let body: ApiEnvelope | null = null;
    if (text.length > 0) {
      try {
        const parsed: unknown = JSON.parse(text);
        body = isRecord(parsed) ? (parsed as ApiEnvelope) : null;
      } catch {
        throw new AdminApiError(502, "invalid_response");
      }
    }

    if (response.status === 401) {
      this.onUnauthorized?.();
      throw new AdminApiError(401, errorCode(body));
    }
    if (!response.ok) {
      throw new AdminApiError(response.status, errorCode(body));
    }
    if (!isRecord(body) || body.ok !== true || !("data" in body)) {
      throw new AdminApiError(502, "invalid_response");
    }
    return body.data as T;
  }
}

export function describeError(error: unknown): string {
  if (!(error instanceof AdminApiError)) {
    return "Unexpected error. Try again.";
  }
  switch (error.code) {
    case "admin_origin_not_configured":
      return "The backend is deployed, but ADMIN_ORIGIN is not configured. No admin request can succeed until the exact Vercel origin is set in Supabase.";
    case "origin_not_allowed":
      return "This browser origin is not the configured ADMIN_ORIGIN. Check the exact Vercel preview URL and the Supabase secret.";
    case "unauthorized":
      return "Your session is missing or expired. Sign in again.";
    case "forbidden":
      return "This account is authenticated but is not an active HMX PDF admin.";
    case "network_error":
      return "Cannot reach the admin backend. Check the network and the Supabase URL.";
    case "timeout":
      return "The admin backend did not respond in time. Try again.";
    case "invalid_response":
      return "The backend returned an unexpected response. No action was confirmed.";
    case "not_configured":
      return "The public Supabase URL or publishable key is missing from this deployment.";
    case "rate_limited":
      return "Too many requests. Wait and try again.";
    default:
      return `${error.code.replaceAll("_", " ")} (HTTP ${error.status || "network"})`;
  }
}
