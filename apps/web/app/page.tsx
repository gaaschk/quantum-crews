import ReleaseStatus from "./release-status";
import JobStarter from "./job-starter";
export const dynamic = "force-dynamic";
async function servicesReady() {
  try { const response = await fetch(`${process.env.API_BASE_URL ?? "http://127.0.0.1:8100"}/api/foundation`, { cache: "no-store", signal: AbortSignal.timeout(3000) }); return response.ok; }
  catch { return false; }
}
export default async function Home() {
 const ready = await servicesReady();
 return <main><header><a href="/" className="brand">Quantum <strong>Crews</strong><span className="brand-dot" aria-hidden="true">✳</span></a><a className="text-button" href="/workspace">Open workspace ↗</a></header>
 <section className="intro"><p className="eyebrow">YOUR WORK, WITH A LITTLE HELP</p><h1>Good work starts<br/>with a great team.</h1><p>Tell us what you have in mind. Make it yours, one step at a time.</p></section>
 <JobStarter/>
 <footer><ReleaseStatus/><span>by Quantum Horizon Innovations</span><div><span role="status" className={ready ? "service-ready" : ""}>{ready ? "Services are ready" : "Services are unavailable — reload after startup"}</span><a href="/signin" className="text-button">Sign in or create an account</a></div></footer></main>;
}
