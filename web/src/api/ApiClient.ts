export type ApiFailureKind = "anonymous" | "forbidden" | "expired" | "conflict" | "degraded" | "invalid" | "unexpected";

export class ApiFailure extends Error {
  public constructor(public readonly kind: ApiFailureKind, public readonly status: number,
    public readonly code: string, message: string) { super(message); }
}

export interface CurrentSession { readonly sessionId: string; readonly accountId: string; readonly authStrength: string; readonly authenticatedAt: string; readonly absoluteExpiresAt: string; readonly revoked: boolean }
export interface RequestSummary { readonly requestId: string; readonly status: string; readonly submittedAt: string }
export interface ServiceSummary { readonly serviceId: string; readonly slug: string; readonly status: string; readonly content?: Record<string, unknown> }
export interface WorkflowSummary { readonly templateId: string; readonly versionId: string; readonly version: number; readonly name: string; readonly status: string; readonly checkpointCount: number }
export interface ProjectSummary { readonly projectId: string; readonly status: string; readonly waitingOn: string; readonly currentCheckpointId?: string | null; readonly version: number }
export interface NotificationSummary { readonly id: string; readonly category: string; readonly templateKey: string; readonly data?: Record<string, unknown>; readonly createdAt: string; readonly readAt?: string | null }
export interface PublicCreator { readonly slug?: string; readonly displayName?: string; readonly bio?: string; readonly categories?: readonly string[]; readonly intakeStatus?: string }
export interface PublicService { readonly slug: string; readonly content: { readonly title?: string; readonly summary?: string; readonly description?: string; readonly revisionPolicy?: string; readonly deliverables?: readonly string[] } }
export interface IntakeForm { readonly schema: Record<string, unknown>; readonly privacyTextVersion: string }
export interface SubmissionReceipt { readonly requestId: string; readonly requestAccessId: string }
export interface BillingSummary { readonly planKey?: string; readonly status: string; readonly currentPeriodEnd?: string; readonly limits?: Record<string, number>; readonly reconciledAt?: string }
export interface AuthFactor { readonly credentialId: string; readonly type: string; readonly status: string }
export interface ReportSummary { readonly id: string; readonly workspaceId: string; readonly subjectType: string; readonly subjectId: string; readonly reasonCode: string; readonly status: string; readonly createdAt: string; readonly resolvedAt?: string | null }
export interface CreatedResource { readonly id: string }
export interface RedirectResource { readonly url: string }

const statusKinds: Partial<Record<number, ApiFailureKind>> = { 401: "anonymous", 403: "forbidden", 409: "conflict", 410: "expired", 423: "forbidden", 503: "degraded" };

export class ApiClient {
  public constructor(private readonly baseUrl = "/api/v1") {}
  public getSession() { return this.request<CurrentSession>("/auth/session"); }
  public authenticate(email: string, password: string, totpCode: string) { return this.request<void>("/auth/sessions", { method: "POST", body: { email, password, totpCode } }); }
  public startEmailVerification(email: string) { return this.request<void>("/auth/email-verifications", { method: "POST", body: { email } }); }
  public startRecovery(email: string) { return this.request<{ readonly recoveryId: string }>("/auth/recoveries", { method: "POST", body: { email } }); }
  public listRequests(workspaceId: string) { return this.request<RequestSummary[]>(`/workspaces/${workspaceId}/requests`); }
  public classifyRequest(workspaceId: string, requestId: string, classification: string, reason: string) { return this.request<void>(`/workspaces/${workspaceId}/requests/${requestId}`, { method: "PATCH", body: { classification, reason } }); }
  public listServices(workspaceId: string) { return this.request<ServiceSummary[]>(`/workspaces/${workspaceId}/services`); }
  public cloneService(workspaceId: string, serviceId: string, slug: string) { return this.request<CreatedResource>(`/workspaces/${workspaceId}/services/${serviceId}/clones`, { method: "POST", body: { slug } }); }
  public listWorkflows(workspaceId: string) { return this.request<WorkflowSummary[]>(`/workspaces/${workspaceId}/workflow-templates`); }
  public listProjects(workspaceId: string) { return this.request<ProjectSummary[]>(`/workspaces/${workspaceId}/projects`); }
  public getProject(workspaceId: string, projectId: string) { return this.request<ProjectSummary>(`/workspaces/${workspaceId}/projects/${projectId}`); }
  public getClientProject(projectAccessId: string) { return this.request<ProjectSummary>(`/client/projects/${projectAccessId}`); }
  public publishProgress(workspaceId: string, checkpointId: string, body: string, clientVisible: boolean) { return this.request<CreatedResource>(`/workspaces/${workspaceId}/checkpoints/${checkpointId}/progress-updates`, { method: "POST", body: { body, visibility: clientVisible ? "client" : "internal" } }); }
  public initiateUpload(workspaceId: string, projectId: string, file: File) { return this.request<CreatedResource>(`/workspaces/${workspaceId}/projects/${projectId}/uploads`, { method: "POST", body: { logicalName: file.name, mediaType: file.type || "application/octet-stream", sizeBytes: file.size } }); }
  public listNotifications() { return this.request<NotificationSummary[]>("/notifications"); }
  public markNotificationRead(notificationId: string) { return this.request<void>(`/notifications/${notificationId}/reads`, { method: "POST" }); }
  public updateNotificationPreference(workspaceId: string, mode: string) { return this.request<void>(`/workspaces/${workspaceId}/notification-preferences`, { method: "PUT", body: { category: "activity", channel: "email", mode } }); }
  public getPublicCreator(slug: string) { return this.request<PublicCreator>(`/public/creators/${encodeURIComponent(slug)}`); }
  public getPublicService(slug: string) { return this.request<PublicService>(`/public/services/${encodeURIComponent(slug)}`); }
  public getIntakeForm(slug: string) { return this.request<IntakeForm>(`/public/services/${encodeURIComponent(slug)}/intake-form`); }
  public submitRequest(slug: string, email: string, summary: string, privacyTextVersion: string) { return this.request<SubmissionReceipt>(`/public/services/${encodeURIComponent(slug)}/requests`, { method: "POST", body: { email, privacyTextVersion, privacyAccepted: true, botPassed: true, answers: { summary } } }); }
  public getBillingSummary(workspaceId: string) { return this.request<BillingSummary>(`/workspaces/${workspaceId}/billing/summary`); }
  public createBillingPortal(workspaceId: string) { return this.request<RedirectResource>(`/workspaces/${workspaceId}/billing/portal-sessions`, { method: "POST", body: { returnUrl: window.location.href } }); }
  public listAuthFactors() { return this.request<AuthFactor[]>("/auth/factors"); }
  public requestDataExport(workspaceId: string) { return this.request<CreatedResource>(`/workspaces/${workspaceId}/data-exports`, { method: "POST", body: { format: "json" } }); }
  public requestWorkspaceDeletion(workspaceId: string) { return this.request<CreatedResource>(`/workspaces/${workspaceId}/deletion-requests`, { method: "POST" }); }
  public listReports() { return this.request<ReportSummary[]>("/admin/reports"); }
  public updateReport(reportId: string, status: string) { return this.request<void>(`/admin/reports/${reportId}`, { method: "PATCH", body: { status } }); }
  public requestSupport(workspaceId: string, projectId: string) { return this.request<CreatedResource>("/admin/support-requests", { method: "POST", body: { workspaceId, projectId, ticketId: `SUP-${Date.now()}`, purpose: "incident-investigation", allowedOperations: ["read_project"] } }); }
  public activateKillSwitch(workspaceId: string, feature: string) { return this.request<CreatedResource>(`/admin/kill-switches/${feature}/activations`, { method: "POST", body: { workspaceId, reason: "operator-request", releaseCondition: "independent-operator-review" } }); }

  private async request<T>(path: string, options: { readonly method?: string; readonly body?: unknown } = {}): Promise<T> {
    let response: Response;
    const method = options.method ?? "GET";
    const headers: Record<string, string> = {};
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
      let csrf = this.csrfCookie();
      if (!csrf) {
        try { await fetch(`${this.baseUrl}/auth/session`, { credentials: "include" }); } catch { /* 本要求が通信エラーを一貫して変換する。 */ }
        csrf = this.csrfCookie();
      }
      if (csrf) headers["X-XSRF-TOKEN"] = decodeURIComponent(csrf);
    }
    try {
      response = await fetch(`${this.baseUrl}${path}`, { method, credentials: "include",
        headers: Object.keys(headers).length ? headers : undefined,
        body: options.body === undefined ? undefined : JSON.stringify(options.body) });
    } catch { throw new ApiFailure("degraded", 0, "NETWORK_UNAVAILABLE", "サーバーへ接続できませんでした。"); }
    if (!response.ok) throw await this.failure(response);
    if (response.status === 204 || response.headers.get("content-length") === "0") return undefined as T;
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  private csrfCookie(): string | undefined {
    return document.cookie.split("; ").find((item) => item.startsWith("XSRF-TOKEN="))?.slice("XSRF-TOKEN=".length);
  }

  private async failure(response: Response): Promise<ApiFailure> {
    let code = `HTTP_${response.status}`;
    let detail = "要求を完了できませんでした。";
    try {
      const problem = await response.json() as { readonly code?: string; readonly detail?: string; readonly title?: string };
      code = problem.code ?? code;
      detail = problem.detail ?? problem.title ?? detail;
    } catch { /* Problem Detailsを返さない境界でもHTTP状態を保持する。 */ }
    return new ApiFailure(statusKinds[response.status] ?? (response.status < 500 ? "invalid" : "unexpected"), response.status, code, detail);
  }
}
