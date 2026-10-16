export async function api<T = unknown>(path: string, body?: unknown, method?: string): Promise<T> {
 const response = await fetch(`/api${path}`, {method: method ?? (body === undefined ? "GET" : "POST"), credentials:"same-origin", headers: body === undefined ? undefined : {"Content-Type":"application/json"}, body: body === undefined ? undefined : JSON.stringify(body), cache:"no-store"});
 const data = await response.json().catch(() => ({}));
 if (!response.ok) { if(response.status === 401 && !path.startsWith("/auth/") && typeof window !== "undefined") window.location.assign("/signin?expired=1"); throw new Error(data.message ?? `Request failed (${response.status})`); }
 return data;
}
export type Definition = {id:string;kind:"agent"|"task"|"crew";revision:number;name:string;spec:Record<string,unknown>;createdAt:string};
export type Me = {user:{id:string;name:string;email:string};organizations:{id:string;name:string;role:string;personal:boolean}[];activeOrganizationId:string};
