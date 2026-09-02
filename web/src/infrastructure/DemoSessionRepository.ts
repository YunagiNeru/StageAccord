import type { SessionRepository, SessionSnapshot } from "../domain/session";

export class DemoSessionRepository implements SessionRepository {
  public async load(): Promise<SessionSnapshot> {
    const forcedState = new URLSearchParams(window.location.search).get("session");
    await Promise.resolve();

    if (forcedState === "anonymous") return { status: "anonymous" };
    if (forcedState === "degraded") return { status: "degraded" };

    return {
      status: "authenticated",
      workspaceName: "Northline Studio",
      actorName: "山田 明",
    };
  }
}
