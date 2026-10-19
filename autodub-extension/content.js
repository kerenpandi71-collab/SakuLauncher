(() => {
 if (window.__sakuAutoDub) return; window.__sakuAutoDub = true;
 let prefs={enabled:false,rate:1,volume:1}, last="", lastSpoken="", timer=null, busy=false, queued="";
 let badge;
 function status(s){ if(!badge || !badge.isConnected){badge=document.createElement("div");badge.id="saku-auto-dub-badge";document.documentElement.appendChild(badge);} badge.textContent=s;badge.style.display="block"; }
 function captions(){return [...document.querySelectorAll(".ytp-caption-segment")].map(n=>(n.textContent||"").trim()).filter(Boolean).join(" ").replace(/\\s+/g," ").trim();}
 function enableCC(){const b=document.querySelector(".ytp-subtitles-button");if(b && b.getAttribute("aria-pressed")!=="true") b.click();}
 function speak(text){
  if(!("speechSynthesis" in window)){status("TTS tidak tersedia di perangkat");return;}
  const u=new SpeechSynthesisUtterance(text);u.lang="id-ID";u.rate=Number(prefs.rate)||1;u.volume=Math.max(0,Math.min(1,Number(prefs.volume)));
  const voices=speechSynthesis.getVoices();const v=voices.find(x=>/^id(-|_)?ID$/i.test(x.lang))||voices.find(x=>/^id/i.test(x.lang));if(v)u.voice=v;
  u.onstart=()=>status("Auto Dub: berbicara Indonesia");u.onend=()=>status("Auto Dub aktif");u.onerror=()=>status("TTS gagal; cek mesin suara perangkat");speechSynthesis.speak(u);
 }
 async function translateSpeak(text){
  if(busy){queued=text;return;}busy=true;status("Menerjemahkan subtitle…");
  try{const r=await browser.runtime.sendMessage({type:"TRANSLATE",text});if(prefs.enabled&&r&&r.ok&&r.text&&r.text!==lastSpoken){lastSpoken=r.text;speak(r.text);}else if(!r||!r.ok)status("Terjemahan gagal; coba lagi");}
  catch(_){status("Layanan terjemahan tidak tersedia");}
  finally{busy=false;if(queued&&prefs.enabled){const q=queued;queued="";if(q!==text)translateSpeak(q);}}
 }
 function check(){
  if(!prefs.enabled)return;const t=captions();if(!t||t===last)return;last=t;clearTimeout(timer);
  timer=setTimeout(()=>{if(!prefs.enabled||t.length<12||t===lastSpoken)return;if(!/[.!?…]$/.test(t)&&t.split(" ").length<4)return;translateSpeak(t);},650);
 }
 new MutationObserver(check).observe(document.documentElement,{childList:true,subtree:true,characterData:true});
 browser.storage.local.get({enabled:false,rate:1,volume:1}).then(p=>{prefs=p;if(prefs.enabled){status("Auto Dub aktif — menunggu CC");setTimeout(enableCC,1200);}});
 browser.runtime.onMessage.addListener(m=>{if(!m)return;if(m.type==="SET_PREFS"){prefs={...prefs,...m.prefs};if(!prefs.enabled)speechSynthesis.cancel();else{status("Auto Dub aktif — menunggu CC");enableCC();}}if(m.type==="STOP_TTS"){speechSynthesis.cancel();status("Suara dihentikan");}if(m.type==="TEST_TTS"){speechSynthesis.cancel();speak("Tes suara SakuTube Auto Dub. Suara bahasa Indonesia gratis dari perangkat.");}});
})();