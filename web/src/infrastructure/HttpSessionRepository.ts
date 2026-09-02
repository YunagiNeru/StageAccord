import type { SessionRepository, SessionSnapshot } from "../domain/session";
import { ApiClient, ApiFailure } from "../api/ApiClient";

export class HttpSessionRepository implements SessionRepository {
  public constructor(private readonly client: ApiClient, private readonly workspaceId: string | null) {}
  public async load(): Promise<SessionSnapshot> {
    if (!this.workspaceId) return { status: "degraded" };
    try {
      const session = await this.client.getSession();
      return { status: "authenticated", workspaceId: this.workspaceId, workspaceName: "ワークスペース", actorName: session.accountId.slice(0, 8) };
    } catch (error) {
      if (error instanceof ApiFailure && error.kind === "anonymous") return { status: "anonymous" };
      return { status: "degraded" };
    }
  }
}
