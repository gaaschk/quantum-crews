"use client";
import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
export default function JobStarter() {
  const [brief, setBrief] = useState("");
  const briefInput = useRef<HTMLTextAreaElement>(null);
  const router = useRouter();
  return (
    <section className="starter" aria-labelledby="start-title">
      <form onSubmit={(event) => { event.preventDefault(); if (brief.trim()) { sessionStorage.setItem("quantum-crews:brief", brief.trim()); router.push("/workspace"); } }}>
        <label id="start-title" htmlFor="brief">What would you like help with?</label>
        <textarea id="brief" ref={briefInput} value={brief} onChange={(event) => setBrief(event.target.value)} maxLength={2000} rows={2} placeholder="Describe a job you’d like a team to help with…" required />
        <div className="starter-actions"><button className="primary" type="submit">Start a draft <span aria-hidden="true">↗</span></button><button className="text-button" type="button" onClick={() => { sessionStorage.removeItem("quantum-crews:brief"); router.push("/workspace?manual=1"); }}>Build manually</button><details className="examples"><summary>Need an idea?</summary><div>{["Review a report and summarize the risks", "Turn meeting notes into next steps", "Draft and review a blog post"].map((example) => <button type="button" key={example} onClick={(event) => { setBrief(example); event.currentTarget.closest("details")?.removeAttribute("open"); briefInput.current?.focus(); }}>{example}</button>)}</div></details></div>
      </form>
    </section>
);
}
