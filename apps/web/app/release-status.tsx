"use client";
import {useEffect,useState} from "react";
type Release={installedRevision:string|null;status:string;checkedAt?:string;commitUrl?:string;latestPrNumber?:number;latestPrUrl?:string};
export default function ReleaseStatus(){
 const [release,setRelease]=useState<Release>(),[error,setError]=useState(false);
 useEffect(()=>{let active=true;async function check(){try{const response=await fetch("/api/release",{cache:"no-store",signal:AbortSignal.timeout(8000)});if(!response.ok)throw new Error();const data=await response.json();if(active){setRelease(data);setError(false);}}catch{if(active)setError(true);}}void check();const timer=setInterval(()=>void check(),60000);return()=>{active=false;clearInterval(timer);};},[]);
 if(error)return <span role="status">Installed version could not be checked. Reload to retry.</span>;
 if(!release)return <span role="status">Checking installed version…</span>;
 if(release.status==="development")return <span>Development build · deployment status unavailable</span>;
 const webRevision=process.env.QUANTUM_SOURCE_REVISION;
 return <span role="status">Installed <a href={release.commitUrl} target="_blank" rel="noreferrer">{release.installedRevision?.slice(0,7)}</a>{" · "}{webRevision&&webRevision!==release.installedRevision?"Web/API versions differ":release.status==="deployed"?<>Latest merged <a href={release.latestPrUrl} target="_blank" rel="noreferrer">PR #{release.latestPrNumber}</a> is deployed</>:release.status==="not_deployed"?<>Latest merged <a href={release.latestPrUrl} target="_blank" rel="noreferrer">PR #{release.latestPrNumber}</a> is not deployed yet</>:"Latest PR comparison unavailable"}{release.checkedAt&&<small title="GitHub comparison refreshes every five minutes"> · checked {new Date(release.checkedAt).toLocaleTimeString()}</small>}</span>;
}
