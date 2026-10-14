import {mountDesktopAdmin} from './desktop-admin.js';
import {wireAccountDeletion,wireVoidLicenseDeletion} from './admin-cleanup.js';
const app = document.querySelector('#app');
const nav = document.querySelector('#nav');
let config={turnstile_site_key:'',email_verification_required:false,mail_ready:false};
let loggedIn=null;
let turnstileToken='';
const escapeHtml = s => String(s ?? '').replace(/[&<>"']/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const humanDate = sec => sec ? new Date(sec*1000).toLocaleDateString('vi-VN',{day:'2-digit',month:'2-digit',year:'numeric'}) : 'Chưa kích hoạt';
const statusName = s => ({pending:'Chờ duyệt',approved:'Đã duyệt',suspended:'Bị khóa'})[s] || s;
const statusClass = s => ({pending:'warn',approved:'good',suspended:'bad'})[s] || '';

async function call(path,opts={}) {
  const r=await fetch(path,{credentials:'same-origin',headers:{'content-type':'application/json',...(opts.headers||{})},...opts});
  const data=await r.json().catch(()=>({error:'Phản hồi máy chủ không hợp lệ'}));
  if(!r.ok)throw new Error(data.error||'Yêu cầu thất bại');
  return data;
}
const post = (url,obj={})=>call(url,{method:'POST',body:JSON.stringify(obj)});
function notice(message,error=false){
  const el=document.querySelector('#flash');if(!el)return;
  el.className=`notice${error?' error':''}`;el.textContent=message;
}
function go(path){history.pushState(null,'',path);render();}
function wireLinks(){
  document.querySelectorAll('a[data-link]').forEach(a=>a.addEventListener('click',e=>{e.preventDefault();go(new URL(a.href).pathname)}));
}
function updateNav(){
  nav.innerHTML = loggedIn ? `<a href="/dashboard" data-link>Tài khoản</a>${loggedIn.role==='admin'?'<a href="/admin" data-link>Quản trị</a>':''}<button class="btn" id="logout" type="button">Đăng xuất</button>` : '<a href="/login" data-link>Đăng nhập</a><a href="/register" data-link class="nav-action">Tạo tài khoản</a>';
  document.querySelector('#logout')?.addEventListener('click',async()=>{try{await post('/api/logout');}catch{}loggedIn=null;go('/login');});
  wireLinks();
}
function formPage(title,sub,fields,button,footer,submit){
  app.innerHTML=`<section class="centered panel"><div class="eyebrow">VNCode Account</div><h1>${title}</h1><p class="muted">${sub}</p><form id="auth-form">${fields}<div class="turnstile" id="captcha"></div><div class="auth-message" id="flash" role="alert"></div><button class="btn btn-primary" id="send" type="submit">${button}</button></form><div class="form-footer">${footer}</div></section>`;
  document.querySelector('#auth-form').addEventListener('submit',async e=>{
    e.preventDefault(); const b=document.querySelector('#send');b.disabled=true;
    try{await submit(new FormData(e.currentTarget));}catch(err){notice(err.message,true);b.disabled=false;resetTurnstile();}
  });
  wireLinks(); setupTurnstile();
}
function field(label,name,type='text',auto='',attr=''){
  return `<div class="field"><label for="${name}">${label}</label><input class="input" id="${name}" name="${name}" type="${type}" autocomplete="${auto}" required ${attr}></div>`;
}
function resetTurnstile(){try{window.turnstile?.reset();turnstileToken='';}catch{}}
async function setupTurnstile(){
  turnstileToken=''; if(!config.turnstile_site_key)return;
  const slot=document.querySelector('#captcha');if(!slot)return;
  if(!window.turnstile){
    if(!document.querySelector('#cf-turnstile-script')){
      const s=document.createElement('script');s.id='cf-turnstile-script';s.src='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';s.async=true;s.defer=true;document.head.appendChild(s);
    }
    for(let i=0;i<40&&!window.turnstile;i++)await new Promise(r=>setTimeout(r,150));
  }
  if(window.turnstile && slot.isConnected)window.turnstile.render(slot,{sitekey:config.turnstile_site_key,callback:t=>turnstileToken=t});
}
function home(){
  app.innerHTML=`<section class="hero"><div><div class="eyebrow">Tài khoản VNCode · Kết nối toàn diện</div><h1>Quản lý quyền sử dụng <span class="text-green">thật đơn giản.</span></h1><p class="hero-copy">Một tài khoản VNCode dành cho đăng ký, đăng nhập và theo dõi trạng thái sử dụng. Quản trị viên kiểm soát việc kích hoạt và gia hạn, không phụ thuộc vào máy chủ Windows tại nhà.</p><div class="actions"><a class="btn btn-primary" href="/register" data-link>Tạo tài khoản miễn phí →</a><a class="btn" href="/login" data-link>Đã có tài khoản</a></div><p class="small" style="margin-top:20px">Việc đăng ký không tự động cấp quyền sử dụng. Tài khoản cần được phê duyệt.</p></div><div class="preview"><div class="window-bar"><span></span><span></span><span></span></div><div class="preview-card"><div class="label">VNCode · Account Center</div><div class="preview-number">Tất cả trong một</div><div class="progress"><i></i></div><div class="stat-row"><span>Đăng ký tài khoản</span><strong>01</strong></div><div class="stat-row"><span>Chờ xác minh & phê duyệt</span><strong>02</strong></div><div class="stat-row"><span>Kích hoạt quyền sử dụng</span><strong>03</strong></div></div><div class="feature-row"><div class="feature-icon">✓</div><div><div class="feature-title">Quản lý giấy phép tập trung</div><div class="feature-sub">Tài khoản, thời hạn và lịch sử thay đổi</div></div></div><div class="feature-row"><div class="feature-icon">↗</div><div><div class="feature-title">Sẵn sàng tích hợp Windows</div><div class="feature-sub">API xác thực dành cho ứng dụng VNCode</div></div></div></div></section>`;
  wireLinks();
}
function register(){
  formPage('Tạo tài khoản VNCode','Đăng ký với email của bạn. Quản trị viên sẽ xét duyệt trước khi bạn được sử dụng.',field('Địa chỉ email','email','email','email')+field('Mật khẩu (tối thiểu 14 ký tự)','password','password','new-password','minlength="14" maxlength="128"')+field('Xác nhận mật khẩu','confirm','password','new-password','minlength="14" maxlength="128"'),'Đăng ký',`<span class="small">Đã có tài khoản? <a href="/login" data-link>Đăng nhập</a></span>`,async f=>{
    await post('/api/register',{email:f.get('email'),password:f.get('password'),confirm:f.get('confirm'),turnstile:turnstileToken});
    notice(config.email_verification_required?'Đăng ký thành công. Kiểm tra email để xác minh, sau đó chờ quản trị viên duyệt.':'Đăng ký thành công. Hãy đăng nhập và chờ quản trị viên duyệt.');
    document.querySelector('#auth-form').reset();
  });
}
function login(){
  formPage('Chào mừng trở lại','Đăng nhập để kiểm tra tài khoản và thời hạn sử dụng của bạn.',field('Địa chỉ email','email','email','username')+field('Mật khẩu','password','password','current-password'),'Đăng nhập',`<span class="small"><a href="/register" data-link>Đăng ký</a></span><span class="small"><a href="/forgot" data-link>Quên mật khẩu?</a></span>`,async f=>{
    const d=await post('/api/login',{email:f.get('email'),password:f.get('password'),turnstile:turnstileToken});loggedIn=d.user;go(d.user.role==='admin'?'/admin':'/dashboard');
  });
}
function forgot(){
  formPage('Khôi phục mật khẩu','Nhập email của bạn để nhận liên kết khôi phục.',field('Email đăng ký','email','email','email'),'Gửi liên kết',`<span class="small"><a href="/login" data-link>Quay lại đăng nhập</a></span>`,async f=>{const d=await post('/api/password/forgot',{email:f.get('email')});notice(d.message);});
}
function reset(token){
  formPage('Đặt lại mật khẩu','Tạo mật khẩu mới cho tài khoản VNCode.',field('Mật khẩu mới','password','password','new-password','minlength="14" maxlength="128"')+field('Nhập lại mật khẩu','confirm','password','new-password','minlength="14" maxlength="128"'),'Đặt lại mật khẩu',`<span class="small"><a href="/login" data-link>Đăng nhập</a></span>`,async f=>{const d=await post('/api/password/reset',{token,password:f.get('password'),confirm:f.get('confirm')});notice(d.message);});
}
function verify(token){
  app.innerHTML='<div class="centered panel"><h1>Xác minh email</h1><div id="flash">Đang kiểm tra...</div><p class="small"><a href="/login" data-link>Đến trang đăng nhập</a></p></div>';
  wireLinks();post('/api/email/verify',{token}).then(d=>notice(d.message)).catch(e=>notice(e.message,true));
}
async function dashboard(){
  if(!loggedIn){go('/login');return;}
  let data;
  try {data=await call('/api/license');loggedIn=data.user;}catch(err){app.innerHTML=`<section class="box"><h2>Lỗi tải license</h2><p>${escapeHtml(err.message)}</p></section>`;return;}
  const u=loggedIn,active=u.active,expires=u.expires_at;
  const history=(data.history||[]).length?data.history.map(h=>`<div class="key-value"><span>•••• ${escapeHtml(h.code_last4)} · ${Number(h.days)} ngày</span><strong>${humanDate(h.claimed_at)}</strong></div>`).join(''):'<p class="small">Chưa đổi mã license nào.</p>';
  app.innerHTML=`<div class="dashboard-head row"><div><div class="eyebrow">VNCode License Center</div><h1>Giấy phép sử dụng của bạn</h1><p class="muted">Theo dõi quyền truy cập và thời hạn do hệ thống cấp.</p></div><span class="chip ${active?'good':'warn'}">${active?'Đang hoạt động':'Chưa kích hoạt / hết hạn'}</span></div>
  <div class="two-column"><section class="box"><h2>Tài khoản</h2><div class="key-value"><span>Email</span><strong>${escapeHtml(u.email)}</strong></div><div class="key-value"><span>Vai trò</span><strong>${u.role==='admin'?'Quản trị viên':'Người dùng'}</strong></div><div class="key-value"><span>Trạng thái</span><strong>${escapeHtml(statusName(u.status))}</strong></div><div class="key-value"><span>Xác minh email</span><strong>${u.email_verified?'Đã xác minh':'Chưa xác minh'}</strong></div><div id="flash"></div>${!u.email_verified&&config.mail_ready?'<button class="btn" id="resend">Gửi lại email xác minh</button>':''}</section>
  <section class="box"><h2>License VNCode</h2><div class="key-value"><span>Quyền truy cập</span><strong>${active?'Có hiệu lực':'Không có hiệu lực'}</strong></div><div class="key-value"><span>Ngày hết hạn</span><strong>${u.role==='admin'?'Không áp dụng':humanDate(expires)}</strong></div><p class="small">Ứng dụng Windows sẽ cần cập nhật để kiểm tra license từ API; việc cấp mã trên website chưa tự động cập nhật ứng dụng Windows cũ.</p>${u.role==='admin'?'':`<form id="redeem-form" class="license-form"><label for="license-code">Nhập mã license được Admin cấp</label><div class="license-form-row"><input class="input" id="license-code" placeholder="VNC-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX" maxlength="43" autocomplete="off" required><button class="btn btn-primary" type="submit" id="redeem-button">Kích hoạt</button></div><div class="auth-message" id="redeem-result" role="alert"></div></form>`}</section></div>
  <section class="box" style="margin-top:22px"><h2>Lịch sử kích hoạt bằng mã</h2>${history}</section>`;
  document.querySelector('#resend')?.addEventListener('click',async()=>{try{const d=await post('/api/email/resend');notice(d.message);}catch(e){notice(e.message,true);}});
  document.querySelector('#redeem-form')?.addEventListener('submit',async e=>{
    e.preventDefault();const btn=document.querySelector('#redeem-button');btn.disabled=true;
    const out=document.querySelector('#redeem-result');
    try {await post('/api/license/redeem',{code:document.querySelector('#license-code').value.trim()});await dashboard();}
    catch(e){out.textContent=e.message;out.className='auth-message error';btn.disabled=false;}
  });
}
async function admin(){
  if(!loggedIn||loggedIn.role!=='admin'){go('/login');return;}
  app.innerHTML=`<div class="dashboard-head row"><div><div class="eyebrow">VNCode License Center</div><h1>Quản lý license</h1><p class="muted">Cấp mã, gia hạn, thu hồi quyền và theo dõi lịch sử.</p></div><a class="btn" href="/dashboard" data-link>Tài khoản của tôi</a></div>
  <section class="box"><div class="row"><h2>Người dùng</h2><button class="btn" id="refresh">Làm mới</button></div><div id="flash" role="alert"></div><div id="user-list" class="admin-list"><div class="loading">Đang tải...</div></div><div class="row" style="margin-top:20px"><span class="small" id="page-label"></span><div><button class="btn" id="prev">← Trước</button> <button class="btn" id="next">Tiếp →</button></div></div></section>
  <section class="box" style="margin-top:24px"><div class="row"><div><h2>Mã license đã phát hành</h2><p class="small">Mã đầy đủ chỉ hiển thị một lần lúc tạo. Chỉ hủy được mã chưa sử dụng.</p></div><button class="btn" id="refresh-keys">Làm mới</button></div><label>Trạng thái <select class="input" id="key-state"><option value="">Tất cả</option><option value="unused">Chưa dùng</option><option value="redeemed">Đã kích hoạt</option><option value="void">Đã hủy</option></select></label><div id="key-list" class="admin-list"><div class="loading">Đang tải...</div></div><div class="row"><button class="btn" id="key-prev">← Trước</button><span id="key-page"></span><button class="btn" id="key-next">Tiếp →</button></div></section>`;
  wireLinks();let page=0,total=0,keyPage=0,keyTotal=0;
  const desktop=await mountDesktopAdmin(app,{call,post,escapeHtml,humanDate,notice,onAccountDeleted:async()=>{await Promise.all([refresh(),refreshKeys()]);}});
  async function refresh(){
    try{
      const d=await call(`/api/admin/users?page=${page}`);total=d.total;
      document.querySelector('#page-label').textContent=`${total} tài khoản · Trang ${page+1}`;
      document.querySelector('#prev').disabled=page===0;document.querySelector('#next').disabled=(page+1)*50>=total;
      document.querySelector('#user-list').innerHTML=d.users.length?d.users.map(u=>`<article class="user-card"><div class="row"><div><div class="user-email">${escapeHtml(u.email)}</div><div class="small">Đăng ký: ${humanDate(u.created_at)} · Vai trò: ${escapeHtml(u.role)}</div></div><span class="chip ${statusClass(u.status)}">${escapeHtml(statusName(u.status))}</span></div><div class="small" style="margin-top:10px">Email: ${u.email_verified?'Đã xác minh':'Chưa xác minh'} · Hạn: ${humanDate(u.expires_at)} · ${u.active?'Có quyền':'Chưa có quyền'}</div>${u.role==='admin'?`<div class="user-actions"><button class="btn btn-primary" data-admin-desktop-license="${escapeHtml(u.id)}" data-email="${escapeHtml(u.email)}">Cấp license VNCode</button><span class="small">Quyền admin website được giữ nguyên.</span></div>`:`<div class="user-actions"><button data-id="${u.id}" data-action="approve" class="btn">Duyệt</button><button data-id="${u.id}" data-action="suspend" class="btn btn-danger">Khóa tài khoản</button><button data-id="${u.id}" data-action="verify" class="btn">Xác minh email</button><button data-id="${u.id}" data-action="grant" class="btn">Gia hạn</button><button data-id="${u.id}" data-action="issue" class="btn btn-primary">Tạo mã license</button><button data-id="${u.id}" data-action="revoke" class="btn btn-danger">Thu hồi quyền</button><button data-delete-user="${escapeHtml(u.id)}" data-email="${escapeHtml(u.email)}" class="btn btn-danger">Xóa Gmail</button></div>`}</article>`).join(''):'<p class="muted">Chưa có tài khoản.</p>';
      document.querySelectorAll('[data-action]').forEach(b=>b.addEventListener('click',async()=>{
        const id=b.dataset.id,action=b.dataset.action;b.disabled=true;
        try{
          if(action==='approve'){if(!confirm('Duyệt tài khoản? Thao tác này chưa cấp thời hạn hoặc khóa license.'))return;await post(`/api/admin/users/${id}/status`,{status:'approved'});}
          if(action==='suspend'){if(!confirm('Khóa tài khoản và thu hồi phiên đăng nhập?'))return;await post(`/api/admin/users/${id}/status`,{status:'suspended'});}
          if(action==='verify'){if(!confirm('Bạn đã kiểm tra quyền sở hữu email?'))return;await post(`/api/admin/users/${id}/verify`,{});}
          if(action==='grant'){const input=prompt('Nhập số ngày gia hạn (1–3650):','30');if(input===null)return;const days=Number(input);if(!Number.isInteger(days)||days<1||days>3650)throw new Error('Nhập số ngày 1–3650');await post(`/api/admin/users/${id}/grant`,{days});}
          if(action==='issue'){
            const input=prompt('Số ngày sử dụng cho mã license mới (1–3650):','30');if(input===null)return;
            const days=Number(input);if(!Number.isInteger(days)||days<1||days>3650)throw new Error('Nhập số ngày 1–3650');
            if(!confirm(`Tạo mã license ${days} ngày, chỉ dùng cho tài khoản này?`))return;
            const result=await post('/api/admin/license-codes',{user_id:id,days});
            prompt('Mã chỉ hiển thị MỘT LẦN. Sao chép để gửi riêng cho khách hàng:',result.code);
          }
          if(action==='revoke'){if(!confirm('Thu hồi NGAY quyền sử dụng, vô hiệu hóa mã chưa dùng và buộc đăng nhập lại? Không xóa tài khoản.'))return;await post(`/api/admin/users/${id}/license/revoke`,{});}
          notice('Cập nhật thành công');await Promise.all([refresh(),refreshKeys()]);
        }catch(e){notice(e.message,true);}finally{b.disabled=false;}
      }));
      wireAccountDeletion(document.querySelector('#user-list'),{post,notice,onDeleted:async()=>{
        if(page>0&&d.users.length===1)page--;
        document.querySelector('#desktop-key').replaceChildren();document.querySelector('#desktop-devices').replaceChildren();
        await Promise.all([refresh(),refreshKeys(),desktop.refresh()]);
      }});
      document.querySelectorAll('[data-admin-desktop-license]').forEach(button=>button.onclick=()=>desktop.focusAccount(button.dataset.email).catch(error=>notice(error.message,true)));
    }catch(e){notice(e.message,true);}
  }
  async function refreshKeys(){
    const list=document.querySelector('#key-list');
    try{
      const state=document.querySelector('#key-state').value||'';
      const d=await call('/api/admin/license-codes?'+new URLSearchParams({page:String(keyPage),state}));keyTotal=d.total;
      if(keyPage>0&&!d.codes.length){keyPage--;return refreshKeys();}
      document.querySelector('#key-page').textContent=`${keyTotal} license · Trang ${keyPage+1}`;
      document.querySelector('#key-prev').disabled=keyPage===0;document.querySelector('#key-next').disabled=(keyPage+1)*50>=keyTotal;
      list.innerHTML=d.codes.length?d.codes.map(k=>`<article class="user-card"><div class="row"><div><strong>•••• ${escapeHtml(k.code_last4)}</strong> · ${Number(k.days)} ngày<div class="small">${escapeHtml(k.assigned_email)} · Tạo: ${humanDate(k.created_at)}</div></div><span class="chip ${k.state==='redeemed'?'good':k.state==='void'?'bad':'warn'}">${({unused:'Chưa dùng',redeemed:'Đã kích hoạt',void:'Đã hủy'})[k.state]||escapeHtml(k.state)}</span></div>${k.redeemed_at?`<p class="small">Kích hoạt: ${humanDate(k.redeemed_at)}</p>`:''}${k.state==='unused'?`<div class="user-actions"><button class="btn btn-danger" data-void="${k.id}">Hủy mã chưa dùng</button></div>`:k.state==='void'?`<div class="user-actions"><button class="btn btn-danger" data-delete-license="${escapeHtml(k.id)}">Xóa license đã hủy</button></div>`:''}</article>`).join(''):'<p class="small">Chưa phát hành mã license nào.</p>';
      list.querySelectorAll('[data-void]').forEach(b=>b.addEventListener('click',async()=>{if(!confirm('Hủy mã chưa dùng? Không thể hoàn tác.'))return;b.disabled=true;try{await post(`/api/admin/license-codes/${b.dataset.void}/void`,{});await refreshKeys();}catch(e){notice(e.message,true);b.disabled=false;}}));
      wireVoidLicenseDeletion(list,{post,notice,onDeleted:refreshKeys});
    }catch(e){list.textContent=e.message;}
  }
  document.querySelector('#refresh').addEventListener('click',refresh);
  document.querySelector('#refresh-keys').addEventListener('click',refreshKeys);
  document.querySelector('#key-state').addEventListener('change',()=>{keyPage=0;refreshKeys();});
  document.querySelector('#key-prev').addEventListener('click',()=>{keyPage=Math.max(0,keyPage-1);refreshKeys();});
  document.querySelector('#key-next').addEventListener('click',()=>{if((keyPage+1)*50<keyTotal){keyPage++;refreshKeys();}});
  document.querySelector('#prev').addEventListener('click',()=>{page=Math.max(0,page-1);refresh();});
  document.querySelector('#next').addEventListener('click',()=>{if((page+1)*50<total){page++;refresh();}});
  await Promise.all([refresh(),refreshKeys()]);
}
async function render(){
  updateNav();
  const url=new URL(location.href);const path=url.pathname;
  document.title=(path==='/register'?'Đăng ký':path==='/login'?'Đăng nhập':path==='/admin'?'Quản trị':'Tài khoản')+' — VNCode';
  if(url.searchParams.has('verify'))return verify(url.searchParams.get('verify'));
  if(url.searchParams.has('reset'))return reset(url.searchParams.get('reset'));
  if(path==='/register')return register();if(path==='/login')return login();if(path==='/forgot')return forgot();
  if(path==='/dashboard')return dashboard();if(path==='/admin')return admin();return home();
}
window.addEventListener('popstate',render);
(async()=>{
  try{config=await call('/api/config');}catch{}
  try{const d=await call('/api/me');loggedIn=d.user;}catch{}
  render();
})();
