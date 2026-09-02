import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { ApiClient, ApiFailure, type ApiFailureKind } from "./ApiClient";

interface ApiEnvironment { readonly client: ApiClient; readonly workspaceId: string | null }
const ApiEnvironmentContext = createContext<ApiEnvironment | null>(null);

export function ApiEnvironmentProvider({ children }: { readonly children: ReactNode }) {
  const value = useMemo<ApiEnvironment>(() => ({
    client: new ApiClient(import.meta.env.VITE_STAGE_ACCORD_API_BASE_URL || "/api/v1"),
    workspaceId: import.meta.env.VITE_STAGE_ACCORD_WORKSPACE_ID || null,
  }), []);
  return <ApiEnvironmentContext.Provider value={value}>{children}</ApiEnvironmentContext.Provider>;
}

export function useApiEnvironment(): ApiEnvironment {
  const value = useContext(ApiEnvironmentContext);
  if (!value) throw new Error("ApiEnvironmentProviderが必要です。");
  return value;
}

export type ResourceState<T> =
  | { readonly status: "loading"; readonly reload: () => void }
  | { readonly status: "ready"; readonly data: T; readonly reload: () => void }
  | { readonly status: "error"; readonly kind: ApiFailureKind; readonly message: string; readonly reload: () => void };

type InternalResourceState<T> =
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly data: T }
  | { readonly status: "error"; readonly kind: ApiFailureKind; readonly message: string };

export function useApiResource<T>(loader: () => Promise<T>, dependencies: readonly unknown[]): ResourceState<T> {
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState<InternalResourceState<T>>({ status: "loading" });
  const reload = useCallback(() => setRevision((value) => value + 1), []);
  useEffect(() => {
    let active = true;
    setState({ status: "loading" });
    loader().then((data) => active && setState({ status: "ready", data }), (error: unknown) => active && setState({
      status: "error", kind: error instanceof ApiFailure ? error.kind : "unexpected",
      message: error instanceof Error ? error.message : "要求を完了できませんでした。",
    }));
    return () => { active = false; };
    // loaderは呼び出し側でuseCallbackにより固定する。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...dependencies, revision]);
  return { ...state, reload } as ResourceState<T>;
}
