export type SessionStatus = "initializing" | "anonymous" | "authenticated" | "degraded";

export interface SessionSnapshot {
  readonly status: SessionStatus;
  readonly workspaceId?: string;
  readonly workspaceName?: string;
  readonly actorName?: string;
}

export interface SessionRepository {
  load(): Promise<SessionSnapshot>;
}

export class SessionService {
  public constructor(private readonly repository: SessionRepository) {}

  public initialize(): Promise<SessionSnapshot> {
    return this.repository.load();
  }
}
